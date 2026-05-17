package dev.trentdb.optimizer;

import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCaseExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExistsSubqueryExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOrderByItem;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.planner.logical.LogicalAggregate;
import dev.trentdb.planner.logical.LogicalDependentJoin;
import dev.trentdb.planner.logical.LogicalExplain;
import dev.trentdb.planner.logical.LogicalFilter;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalJoin;
import dev.trentdb.planner.logical.LogicalLimit;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalOrder;
import dev.trentdb.planner.logical.LogicalProjection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

final class ProjectionPruner {
    LogicalOperator prune(LogicalOperator operator) {
        if (operator instanceof LogicalExplain explain) {
            PruneResult child = prune(explain.child(), requiredAll(outputColumnCount(explain.child())));
            return child.operator() == explain.child() ? explain : new LogicalExplain(child.operator());
        }
        return prune(operator, requiredAll(outputColumnCount(operator))).operator();
    }

    private PruneResult prune(LogicalOperator operator, RequiredColumns required) {
        return switch (operator) {
            case LogicalAggregate aggregate -> pruneAggregate(aggregate, required);
            case LogicalDependentJoin join -> new PruneResult(
                    join,
                    OrdinalMapping.identity(outputColumnCount(join)),
                    outputColumnCount(join)
            );
            case LogicalExplain explain -> new PruneResult(explain, OrdinalMapping.identity(1), 1);
            case LogicalFilter filter -> pruneFilter(filter, required);
            case LogicalGet get -> pruneGet(get, required);
            case LogicalJoin join -> pruneJoin(join, required);
            case LogicalLimit limit -> pruneLimit(limit, required);
            case LogicalOrder order -> pruneOrder(order, required);
            case LogicalProjection projection -> pruneProjection(projection, required);
        };
    }

    private PruneResult pruneAggregate(LogicalAggregate aggregate, RequiredColumns required) {
        RequiredColumns aggregateOutputs = required.nonEmpty();
        RequiredColumns childRequired = new RequiredColumns();
        for (BoundExpression group : aggregate.groups()) {
            collectColumns(group, childRequired, outputColumnCount(aggregate.child()));
        }
        for (Integer ordinal : aggregateOutputs.values()) {
            collectColumns(aggregate.selectList().get(ordinal), childRequired, outputColumnCount(aggregate.child()));
        }

        PruneResult child = prune(aggregate.child(), childRequired);
        ArrayList<BoundExpression> groups = remapExpressions(aggregate.groups(), child.mapping());
        ArrayList<BoundExpression> selectList = new ArrayList<>(aggregateOutputs.size());
        ArrayList<String> names = new ArrayList<>(aggregateOutputs.size());
        ArrayList<Integer> kept = new ArrayList<>(aggregateOutputs.size());
        for (Integer ordinal : aggregateOutputs.values()) {
            selectList.add(remap(aggregate.selectList().get(ordinal), child.mapping()));
            names.add(aggregate.selectNames().get(ordinal));
            kept.add(ordinal);
        }

        LogicalAggregate rewritten = new LogicalAggregate(groups, selectList, names, child.operator());
        boolean unchanged = kept.size() == aggregate.selectList().size()
                && rewritten.child() == aggregate.child()
                && rewritten.groups().equals(aggregate.groups())
                && rewritten.selectList().equals(aggregate.selectList());
        return new PruneResult(
                unchanged ? aggregate : rewritten,
                OrdinalMapping.fromKept(aggregate.selectList().size(), kept),
                kept.size()
        );
    }
    private PruneResult pruneFilter(LogicalFilter filter, RequiredColumns required) {
        RequiredColumns childRequired = required.copy();
        collectColumns(filter.predicate(), childRequired, outputColumnCount(filter.child()));
        PruneResult child = prune(filter.child(), childRequired);
        BoundExpression predicate = remap(filter.predicate(), child.mapping());
        LogicalFilter rewritten = new LogicalFilter(predicate, child.operator());
        return new PruneResult(
                predicate == filter.predicate() && child.operator() == filter.child() ? filter : rewritten,
                child.mapping(),
                child.outputCount()
        );
    }
    private PruneResult pruneGet(LogicalGet get, RequiredColumns required) {
        RequiredColumns projectedRequired = required.nonEmpty();
        ArrayList<Integer> keptOutputOrdinals = new ArrayList<>(projectedRequired.size());
        ArrayList<Integer> keptTableOrdinals = new ArrayList<>(projectedRequired.size());
        for (Integer outputOrdinal : projectedRequired.values()) {
            keptOutputOrdinals.add(outputOrdinal);
            keptTableOrdinals.add(get.projectedOrdinals().get(outputOrdinal));
        }
        LogicalGet rewritten = new LogicalGet(get.tableRef(), keptTableOrdinals);
        return new PruneResult(
                keptOutputOrdinals.size() == get.projectedOrdinals().size() ? get : rewritten,
                OrdinalMapping.fromKept(get.projectedOrdinals().size(), keptOutputOrdinals),
                keptOutputOrdinals.size()
        );
    }
    private PruneResult pruneJoin(LogicalJoin join, RequiredColumns required) {
        int leftCount = outputColumnCount(join.left());
        int rightCount = outputColumnCount(join.right());
        RequiredColumns leftRequired = new RequiredColumns();
        RequiredColumns rightRequired = new RequiredColumns();
        splitRequiredColumns(required, leftCount, leftRequired, rightRequired);
        RequiredColumns conditionColumns = new RequiredColumns();
        collectColumns(join.condition(), conditionColumns, leftCount + rightCount);
        splitRequiredColumns(conditionColumns, leftCount, leftRequired, rightRequired);

        PruneResult left = prune(join.left(), leftRequired);
        PruneResult right = prune(join.right(), rightRequired);
        OrdinalMapping mapping = joinMapping(leftCount, left, rightCount, right);
        BoundExpression condition = join.condition() == null ? null : remap(join.condition(), mapping);
        LogicalJoin rewritten = new LogicalJoin(left.operator(), right.operator(), condition, join.joinType());
        boolean unchanged = rewritten.left() == join.left()
                && rewritten.right() == join.right()
                && condition == join.condition();
        return new PruneResult(
                unchanged ? join : rewritten,
                mapping,
                left.outputCount() + right.outputCount()
        );
    }
    private PruneResult pruneLimit(LogicalLimit limit, RequiredColumns required) {
        PruneResult child = prune(limit.child(), required);
        LogicalLimit rewritten = new LogicalLimit(limit.limit(), child.operator());
        return new PruneResult(
                child.operator() == limit.child() ? limit : rewritten,
                child.mapping(),
                child.outputCount()
        );
    }
    private PruneResult pruneOrder(LogicalOrder order, RequiredColumns required) {
        RequiredColumns childRequired = required.copy();
        for (BoundOrderByItem item : order.orders()) {
            collectColumns(item.expression(), childRequired, outputColumnCount(order.child()));
        }
        PruneResult child = prune(order.child(), childRequired);
        ArrayList<BoundOrderByItem> orders = remapOrderItems(order.orders(), child.mapping());
        LogicalOrder rewritten = new LogicalOrder(orders, child.operator());
        return new PruneResult(
                ordersEqualByIdentity(order.orders(), orders) && child.operator() == order.child() ? order : rewritten,
                child.mapping(),
                child.outputCount()
        );
    }
    private PruneResult pruneProjection(LogicalProjection projection, RequiredColumns required) {
        RequiredColumns projectedRequired = required.nonEmpty();
        RequiredColumns childRequired = new RequiredColumns();
        int childColumnCount = outputColumnCount(projection.child());
        for (Integer ordinal : projectedRequired.values()) {
            collectColumns(projection.expressions().get(ordinal), childRequired, childColumnCount);
        }

        PruneResult child = prune(projection.child(), childRequired);
        ArrayList<BoundExpression> expressions = new ArrayList<>(projectedRequired.size());
        ArrayList<String> names = new ArrayList<>(projectedRequired.size());
        ArrayList<Integer> kept = new ArrayList<>(projectedRequired.size());
        for (Integer ordinal : projectedRequired.values()) {
            expressions.add(remap(projection.expressions().get(ordinal), child.mapping()));
            names.add(projection.names().get(ordinal));
            kept.add(ordinal);
        }
        LogicalProjection rewritten = new LogicalProjection(expressions, names, child.operator());
        boolean unchanged = kept.size() == projection.expressions().size()
                && rewritten.child() == projection.child()
                && rewritten.expressions().equals(projection.expressions());
        return new PruneResult(
                unchanged ? projection : rewritten,
                OrdinalMapping.fromKept(projection.expressions().size(), kept),
                kept.size()
        );
    }
    private void splitRequiredColumns(
            RequiredColumns required,
            int leftCount,
            RequiredColumns leftRequired,
            RequiredColumns rightRequired
    ) {
        for (Integer ordinal : required.values()) {
            if (ordinal < leftCount) {
                leftRequired.add(ordinal);
            } else {
                rightRequired.add(ordinal - leftCount);
            }
        }
    }
    private void collectColumns(BoundExpression expression, RequiredColumns required, int sourceColumnCount) {
        if (expression == null) {
            return;
        }
        switch (expression) {
            case BoundAggregateExpression aggregate -> collectExpressions(aggregate.arguments(), required, sourceColumnCount);
            case BoundBetweenExpression between -> {
                collectColumns(between.input(), required, sourceColumnCount);
                collectColumns(between.lower(), required, sourceColumnCount);
                collectColumns(between.upper(), required, sourceColumnCount);
            }
            case BoundBinaryExpression binary -> {
                collectColumns(binary.left(), required, sourceColumnCount);
                collectColumns(binary.right(), required, sourceColumnCount);
            }
            case BoundCaseExpression caseExpression -> collectCaseColumns(caseExpression, required, sourceColumnCount);
            case BoundCastExpression cast -> collectColumns(cast.child(), required, sourceColumnCount);
            case BoundColumnRefExpression column -> required.addIfPresent(column.ordinal(), sourceColumnCount);
            case BoundExistsSubqueryExpression exists -> collectCorrelatedColumns(exists.correlatedColumns(), required);
            case BoundFunctionExpression function -> collectExpressions(function.arguments(), required, sourceColumnCount);
            case BoundInExpression in -> {
                collectColumns(in.input(), required, sourceColumnCount);
                collectExpressions(in.candidates(), required, sourceColumnCount);
            }
            case BoundInSubqueryExpression in -> {
                collectColumns(in.input(), required, sourceColumnCount);
                required.addRange(sourceColumnCount);
            }
            case BoundIntervalExpression ignored -> {
            }
            case BoundLiteralExpression ignored -> {
            }
            case BoundOutputColumnExpression output -> required.addIfPresent(output.ordinal(), sourceColumnCount);
            case BoundSubqueryExpression subquery -> collectCorrelatedColumns(subquery.correlatedColumns(), required);
        }
    }
    private void collectCaseColumns(
            BoundCaseExpression caseExpression,
            RequiredColumns required,
            int sourceColumnCount
    ) {
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            collectColumns(branch.condition(), required, sourceColumnCount);
            collectColumns(branch.result(), required, sourceColumnCount);
        }
        collectColumns(caseExpression.elseExpression(), required, sourceColumnCount);
    }
    private void collectExpressions(
            List<BoundExpression> expressions,
            RequiredColumns required,
            int sourceColumnCount
    ) {
        for (BoundExpression expression : expressions) {
            collectColumns(expression, required, sourceColumnCount);
        }
    }
    private void collectCorrelatedColumns(
            List<BoundExistsSubqueryExpression.CorrelatedColumn> columns,
            RequiredColumns required
    ) {
        for (BoundExistsSubqueryExpression.CorrelatedColumn column : columns) {
            required.add(column.outerOrdinal());
        }
    }

    private BoundExpression remap(BoundExpression expression, OrdinalMapping mapping) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> new BoundAggregateExpression(
                    aggregate.function(),
                    remapExpressions(aggregate.arguments(), mapping),
                    aggregate.starArgument(),
                    aggregate.distinct()
            );
            case BoundBetweenExpression between -> new BoundBetweenExpression(
                    remap(between.input(), mapping),
                    remap(between.lower(), mapping),
                    remap(between.upper(), mapping)
            );
            case BoundBinaryExpression binary -> new BoundBinaryExpression(
                    remap(binary.left(), mapping),
                    binary.operator(),
                    remap(binary.right(), mapping),
                    binary.logicalType()
            );
            case BoundCaseExpression caseExpression -> remapCase(caseExpression, mapping);
            case BoundCastExpression cast -> new BoundCastExpression(remap(cast.child(), mapping), cast.logicalType());
            case BoundColumnRefExpression column -> new BoundColumnRefExpression(
                    column.column(),
                    mapping.map(column.ordinal())
            );
            case BoundExistsSubqueryExpression exists -> new BoundExistsSubqueryExpression(
                    exists.subquery(),
                    exists.localColumnCount(),
                    remapCorrelatedColumns(exists.correlatedColumns(), mapping)
            );
            case BoundFunctionExpression function -> new BoundFunctionExpression(
                    function.function(),
                    remapExpressions(function.arguments(), mapping)
            );
            case BoundInExpression in -> new BoundInExpression(
                    remap(in.input(), mapping),
                    remapExpressions(in.candidates(), mapping),
                    in.negated()
            );
            case BoundInSubqueryExpression in -> new BoundInSubqueryExpression(
                    remap(in.input(), mapping),
                    in.subquery(),
                    in.negated()
            );
            case BoundIntervalExpression interval -> interval;
            case BoundLiteralExpression literal -> literal;
            case BoundOutputColumnExpression output -> new BoundOutputColumnExpression(
                    output.name(),
                    mapping.map(output.ordinal()),
                    output.logicalType()
            );
            case BoundSubqueryExpression subquery -> new BoundSubqueryExpression(
                    subquery.subquery(),
                    subquery.logicalType(),
                    subquery.localColumnCount(),
                    remapCorrelatedColumns(subquery.correlatedColumns(), mapping)
            );
        };
    }

    private ArrayList<BoundExpression> remapExpressions(List<BoundExpression> expressions, OrdinalMapping mapping) {
        ArrayList<BoundExpression> remapped = new ArrayList<>(expressions.size());
        for (BoundExpression expression : expressions) {
            remapped.add(remap(expression, mapping));
        }
        return remapped;
    }

    private BoundCaseExpression remapCase(BoundCaseExpression caseExpression, OrdinalMapping mapping) {
        ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            branches.add(new BoundCaseExpression.WhenClause(
                    remap(branch.condition(), mapping),
                    remap(branch.result(), mapping)
            ));
        }
        return new BoundCaseExpression(
                branches,
                remap(caseExpression.elseExpression(), mapping),
                caseExpression.logicalType()
        );
    }

    private ArrayList<BoundOrderByItem> remapOrderItems(List<BoundOrderByItem> items, OrdinalMapping mapping) {
        ArrayList<BoundOrderByItem> remapped = new ArrayList<>(items.size());
        for (BoundOrderByItem item : items) {
            remapped.add(new BoundOrderByItem(remap(item.expression(), mapping), item.direction()));
        }
        return remapped;
    }

    private List<BoundExistsSubqueryExpression.CorrelatedColumn> remapCorrelatedColumns(
            List<BoundExistsSubqueryExpression.CorrelatedColumn> columns,
            OrdinalMapping mapping
    ) {
        ArrayList<BoundExistsSubqueryExpression.CorrelatedColumn> remapped = new ArrayList<>(columns.size());
        for (BoundExistsSubqueryExpression.CorrelatedColumn column : columns) {
            remapped.add(new BoundExistsSubqueryExpression.CorrelatedColumn(
                    column.name(),
                    column.logicalType(),
                    mapping.map(column.outerOrdinal())
            ));
        }
        return List.copyOf(remapped);
    }

    private boolean ordersEqualByIdentity(List<BoundOrderByItem> left, List<BoundOrderByItem> right) {
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index).expression() != right.get(index).expression()) {
                return false;
            }
        }
        return true;
    }

    private OrdinalMapping joinMapping(int leftCount, PruneResult left, int rightCount, PruneResult right) {
        int[] oldToNew = new int[leftCount + rightCount];
        Arrays.fill(oldToNew, -1);
        for (int ordinal = 0; ordinal < leftCount; ordinal++) {
            if (left.mapping().has(ordinal)) {
                oldToNew[ordinal] = left.mapping().map(ordinal);
            }
        }
        for (int ordinal = 0; ordinal < rightCount; ordinal++) {
            if (right.mapping().has(ordinal)) {
                oldToNew[leftCount + ordinal] = left.outputCount() + right.mapping().map(ordinal);
            }
        }
        return new OrdinalMapping(oldToNew);
    }

    private int outputColumnCount(LogicalOperator operator) {
        return switch (operator) {
            case LogicalAggregate aggregate -> aggregate.selectList().size();
            case LogicalDependentJoin join -> outputColumnCount(join.child()) + 1;
            case LogicalExplain ignored -> 1;
            case LogicalFilter filter -> outputColumnCount(filter.child());
            case LogicalGet get -> get.projectedOrdinals().size();
            case LogicalJoin join -> outputColumnCount(join.left()) + outputColumnCount(join.right());
            case LogicalLimit limit -> outputColumnCount(limit.child());
            case LogicalOrder order -> outputColumnCount(order.child());
            case LogicalProjection projection -> projection.expressions().size();
        };
    }

    private RequiredColumns requiredAll(int outputCount) {
        return new RequiredColumns().addRange(outputCount);
    }

    private record PruneResult(LogicalOperator operator, OrdinalMapping mapping, int outputCount) {}

    private record OrdinalMapping(int[] oldToNew) {
        private static OrdinalMapping identity(int size) {
            int[] oldToNew = new int[size];
            for (int index = 0; index < size; index++) {
                oldToNew[index] = index;
            }
            return new OrdinalMapping(oldToNew);
        }

        private static OrdinalMapping fromKept(int oldCount, List<Integer> kept) {
            int[] oldToNew = new int[oldCount];
            Arrays.fill(oldToNew, -1);
            for (int index = 0; index < kept.size(); index++) {
                oldToNew[kept.get(index)] = index;
            }
            return new OrdinalMapping(oldToNew);
        }

        private int map(int oldOrdinal) {
            if (!has(oldOrdinal)) {
                throw new IllegalStateException("Required column was pruned: " + oldOrdinal);
            }
            return oldToNew[oldOrdinal];
        }

        private boolean has(int oldOrdinal) {
            return oldOrdinal >= 0 && oldOrdinal < oldToNew.length && oldToNew[oldOrdinal] >= 0;
        }
    }

    private static final class RequiredColumns {
        private final TreeSet<Integer> ordinals = new TreeSet<>();

        private RequiredColumns add(int ordinal) {
            if (ordinal >= 0) {
                ordinals.add(ordinal);
            }
            return this;
        }

        private void addIfPresent(int ordinal, int sourceColumnCount) {
            if (ordinal >= 0 && ordinal < sourceColumnCount) {
                ordinals.add(ordinal);
            }
        }

        private RequiredColumns addRange(int count) {
            for (int index = 0; index < count; index++) {
                ordinals.add(index);
            }
            return this;
        }

        private RequiredColumns copy() {
            RequiredColumns copy = new RequiredColumns();
            copy.ordinals.addAll(ordinals);
            return copy;
        }

        private RequiredColumns nonEmpty() {
            if (!ordinals.isEmpty()) {
                return this;
            }
            return new RequiredColumns().add(0);
        }

        private int size() {
            return ordinals.size();
        }

        private Iterable<Integer> values() {
            return ordinals;
        }
    }
}
