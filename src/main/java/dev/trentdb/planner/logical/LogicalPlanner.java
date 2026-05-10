package dev.trentdb.planner.logical;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCaseExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExistsSubqueryExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundExpressionTypes;
import dev.trentdb.planner.BoundExplainStatement;
import dev.trentdb.planner.BoundFrom;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundJoinRef;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.BoundStatement;
import dev.trentdb.planner.BoundSubqueryRef;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.planner.BinderException;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

public final class LogicalPlanner {
    private record AggregatePlan(
            List<BoundExpression> aggregateOutputs,
            List<String> aggregateNames,
            List<BoundExpression> projections,
            BoundExpression having
    ) {
        private AggregatePlan {
            aggregateOutputs = List.copyOf(aggregateOutputs);
            aggregateNames = List.copyOf(aggregateNames);
            projections = List.copyOf(projections);
        }
    }

    private record DependentRewrite(LogicalOperator root, BoundExpression expression) {
    }

    private record ExpressionListRewrite(LogicalOperator root, List<BoundExpression> expressions) {
        private ExpressionListRewrite {
            expressions = List.copyOf(expressions);
        }
    }

    public LogicalOperator plan(BoundStatement statement) {
        if (statement instanceof BoundExplainStatement explain) {
            return new LogicalExplain(plan(explain.statement()));
        }
        if (statement instanceof BoundSelectStatement select) {
            return planSelect(select);
        }
        throw new BinderException("Unsupported bound statement for logical planning: " + statement.getClass().getSimpleName());
    }

    private LogicalOperator planSelect(BoundSelectStatement statement) {
        LogicalOperator root = planFrom(statement.from());
        if (statement.where() != null) {
            root = planWhere(root, statement.where());
        }
        if (statement.isAggregateQuery()) {
            AggregatePlan aggregatePlan = aggregatePlan(statement);
            root = new LogicalAggregate(
                    statement.groupBy(),
                    aggregatePlan.aggregateOutputs(),
                    aggregatePlan.aggregateNames(),
                    root
            );
            if (aggregatePlan.having() != null) {
                root = new LogicalFilter(aggregatePlan.having(), root);
            }
            root = new LogicalProjection(aggregatePlan.projections(), statement.selectNames(), root);
            if (!statement.orderBy().isEmpty()) {
                root = new LogicalOrder(statement.orderBy(), root);
            }
        } else {
            if (statement.having() != null) {
                root = new LogicalFilter(statement.having(), root);
            }
            if (!statement.orderBy().isEmpty()) {
                root = new LogicalOrder(statement.orderBy(), root);
            }
            root = new LogicalProjection(statement.selectList(), statement.selectNames(), root);
        }
        if (statement.limit() != null) {
            root = new LogicalLimit(statement.limit(), root);
        }
        return root;
    }

    private LogicalOperator planWhere(LogicalOperator root, BoundExpression where) {
        if (!containsDependentSubquery(where)) {
            return new LogicalFilter(where, root);
        }
        ArrayList<BoundExpression> pushdown = new ArrayList<>();
        ArrayList<BoundExpression> dependent = new ArrayList<>();
        splitDependentConjuncts(where, pushdown, dependent);
        if (!pushdown.isEmpty()) {
            root = new LogicalFilter(combineConjuncts(pushdown), root);
        }
        BoundExpression dependentPredicate = combineConjuncts(dependent);
        if (dependentPredicate == null) {
            return root;
        }
        DependentRewrite rewrite = rewriteDependentSubqueries(root, dependentPredicate);
        return new LogicalFilter(rewrite.expression(), rewrite.root());
    }

    private void splitDependentConjuncts(
            BoundExpression expression,
            List<BoundExpression> pushdown,
            List<BoundExpression> dependent
    ) {
        if (expression instanceof BoundBinaryExpression binary && binary.operator() == BinaryOperator.AND) {
            splitDependentConjuncts(binary.left(), pushdown, dependent);
            splitDependentConjuncts(binary.right(), pushdown, dependent);
            return;
        }
        if (containsDependentSubquery(expression)) {
            dependent.add(expression);
            return;
        }
        pushdown.add(expression);
    }

    private BoundExpression combineConjuncts(List<BoundExpression> conjuncts) {
        if (conjuncts.isEmpty()) {
            return null;
        }
        BoundExpression result = conjuncts.getFirst();
        for (int index = 1; index < conjuncts.size(); index++) {
            result = new BoundBinaryExpression(result, BinaryOperator.AND, conjuncts.get(index), LogicalType.BOOLEAN);
        }
        return result;
    }

    private LogicalOperator planFrom(BoundFrom from) {
        if (from instanceof BoundTableRef tableRef) {
            return new LogicalGet(tableRef);
        }
        if (from instanceof BoundSubqueryRef subqueryRef) {
            return planSelect(subqueryRef.subquery());
        }
        if (from instanceof BoundJoinRef joinRef) {
            return new LogicalJoin(planFrom(joinRef.left()), new LogicalGet(joinRef.right()), joinRef.condition(), joinRef.type());
        }
        throw new BinderException("Unsupported bound FROM source: " + from.getClass().getSimpleName());
    }

    private AggregatePlan aggregatePlan(BoundSelectStatement statement) {
        ArrayList<BoundExpression> outputs = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < statement.groupBy().size(); groupIndex++) {
            outputs.add(statement.groupBy().get(groupIndex));
            names.add("#group" + groupIndex);
        }

        ArrayList<BoundExpression> projections = new ArrayList<>(statement.selectList().size());
        for (BoundExpression expression : statement.selectList()) {
            projections.add(rewriteAggregateProjection(expression, statement.groupBy(), outputs, names));
        }
        BoundExpression having = statement.having() == null
                ? null
                : rewriteAggregateProjection(statement.having(), statement.groupBy(), outputs, names);
        return new AggregatePlan(outputs, names, projections, having);
    }

    private BoundExpression rewriteAggregateProjection(
            BoundExpression expression,
            List<BoundExpression> groups,
            ArrayList<BoundExpression> outputs,
            ArrayList<String> names
    ) {
        int groupOrdinal = ordinalOf(groups, expression);
        if (groupOrdinal >= 0) {
            return outputColumn(outputs, names, groupOrdinal);
        }
        return switch (expression) {
            case BoundAggregateExpression aggregate -> {
                int ordinal = appendOutput(outputs, names, aggregate, "#aggregate" + outputs.size());
                yield outputColumn(outputs, names, ordinal);
            }
            case BoundBetweenExpression between -> new BoundBetweenExpression(
                    rewriteAggregateProjection(between.input(), groups, outputs, names),
                    rewriteAggregateProjection(between.lower(), groups, outputs, names),
                    rewriteAggregateProjection(between.upper(), groups, outputs, names)
            );
            case BoundBinaryExpression binary -> new BoundBinaryExpression(
                    rewriteAggregateProjection(binary.left(), groups, outputs, names),
                    binary.operator(),
                    rewriteAggregateProjection(binary.right(), groups, outputs, names),
                    binary.logicalType()
            );
            case BoundCaseExpression caseExpression -> rewriteCaseExpression(caseExpression, groups, outputs, names);
            case BoundCastExpression cast -> new BoundCastExpression(
                    rewriteAggregateProjection(cast.child(), groups, outputs, names),
                    cast.logicalType()
            );
            case BoundFunctionExpression function -> rewriteFunctionExpression(function, groups, outputs, names);
            case BoundInExpression in -> rewriteInExpression(in, groups, outputs, names);
            case BoundInSubqueryExpression in -> new BoundInSubqueryExpression(
                    rewriteAggregateProjection(in.input(), groups, outputs, names),
                    in.subquery(),
                    in.negated()
            );
            case BoundExistsSubqueryExpression exists -> exists;
            case BoundIntervalExpression interval -> interval;
            case BoundLiteralExpression literal -> literal;
            case BoundSubqueryExpression subquery -> subquery;
            case BoundColumnRefExpression column -> throw new BinderException(
                    "Column must appear in GROUP BY or be used in an aggregate function: " + column.name());
            case BoundOutputColumnExpression output -> output;
        };
    }

    private BoundCaseExpression rewriteCaseExpression(
            BoundCaseExpression caseExpression,
            List<BoundExpression> groups,
            ArrayList<BoundExpression> outputs,
            ArrayList<String> names
    ) {
        ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            branches.add(new BoundCaseExpression.WhenClause(
                    rewriteAggregateProjection(branch.condition(), groups, outputs, names),
                    rewriteAggregateProjection(branch.result(), groups, outputs, names)
            ));
        }
        return new BoundCaseExpression(
                branches,
                rewriteAggregateProjection(caseExpression.elseExpression(), groups, outputs, names),
                caseExpression.logicalType()
        );
    }

    private BoundFunctionExpression rewriteFunctionExpression(
            BoundFunctionExpression function,
            List<BoundExpression> groups,
            ArrayList<BoundExpression> outputs,
            ArrayList<String> names
    ) {
        ArrayList<BoundExpression> arguments = new ArrayList<>(function.arguments().size());
        for (BoundExpression argument : function.arguments()) {
            arguments.add(rewriteAggregateProjection(argument, groups, outputs, names));
        }
        return new BoundFunctionExpression(function.function(), arguments);
    }

    private BoundInExpression rewriteInExpression(
            BoundInExpression in,
            List<BoundExpression> groups,
            ArrayList<BoundExpression> outputs,
            ArrayList<String> names
    ) {
        ArrayList<BoundExpression> candidates = new ArrayList<>(in.candidates().size());
        for (BoundExpression candidate : in.candidates()) {
            candidates.add(rewriteAggregateProjection(candidate, groups, outputs, names));
        }
        return new BoundInExpression(
                rewriteAggregateProjection(in.input(), groups, outputs, names),
                candidates,
                in.negated()
        );
    }

    private int appendOutput(
            ArrayList<BoundExpression> outputs,
            ArrayList<String> names,
            BoundExpression expression,
            String name
    ) {
        int ordinal = ordinalOf(outputs, expression);
        if (ordinal >= 0) {
            return ordinal;
        }
        outputs.add(expression);
        names.add(name);
        return outputs.size() - 1;
    }

    private int ordinalOf(List<BoundExpression> expressions, BoundExpression candidate) {
        for (int index = 0; index < expressions.size(); index++) {
            if (expressions.get(index).equals(candidate)) {
                return index;
            }
        }
        return -1;
    }

    private BoundOutputColumnExpression outputColumn(
            List<BoundExpression> outputs,
            List<String> names,
            int ordinal
    ) {
        return new BoundOutputColumnExpression(
                names.get(ordinal),
                ordinal,
                BoundExpressionTypes.logicalType(outputs.get(ordinal))
        );
    }

    private DependentRewrite rewriteDependentSubqueries(LogicalOperator root, BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> rewriteAggregate(root, aggregate);
            case BoundBetweenExpression between -> rewriteBetween(root, between);
            case BoundBinaryExpression binary -> rewriteBinary(root, binary);
            case BoundCaseExpression caseExpression -> rewriteCase(root, caseExpression);
            case BoundCastExpression cast -> {
                DependentRewrite child = rewriteDependentSubqueries(root, cast.child());
                yield new DependentRewrite(child.root(), new BoundCastExpression(child.expression(), cast.logicalType()));
            }
            case BoundExistsSubqueryExpression exists -> rewriteExists(root, exists);
            case BoundFunctionExpression function -> rewriteFunction(root, function);
            case BoundInExpression in -> rewriteIn(root, in);
            case BoundInSubqueryExpression in -> {
                DependentRewrite input = rewriteDependentSubqueries(root, in.input());
                yield new DependentRewrite(input.root(), new BoundInSubqueryExpression(input.expression(), in.subquery(), in.negated()));
            }
            case BoundColumnRefExpression column -> new DependentRewrite(root, column);
            case BoundIntervalExpression interval -> new DependentRewrite(root, interval);
            case BoundLiteralExpression literal -> new DependentRewrite(root, literal);
            case BoundOutputColumnExpression output -> new DependentRewrite(root, output);
            case BoundSubqueryExpression subquery -> rewriteScalar(root, subquery);
        };
    }

    private boolean containsDependentSubquery(BoundExpression expression) {
        if (expression == null) {
            return false;
        }
        return switch (expression) {
            case BoundAggregateExpression aggregate -> containsDependentSubquery(aggregate.arguments());
            case BoundBetweenExpression between -> containsDependentSubquery(between.input())
                    || containsDependentSubquery(between.lower())
                    || containsDependentSubquery(between.upper());
            case BoundBinaryExpression binary -> containsDependentSubquery(binary.left())
                    || containsDependentSubquery(binary.right());
            case BoundCaseExpression caseExpression -> containsDependentSubquery(caseExpression);
            case BoundCastExpression cast -> containsDependentSubquery(cast.child());
            case BoundExistsSubqueryExpression exists -> exists.isCorrelated();
            case BoundFunctionExpression function -> containsDependentSubquery(function.arguments());
            case BoundInExpression in -> containsDependentSubquery(in.input()) || containsDependentSubquery(in.candidates());
            case BoundInSubqueryExpression in -> containsDependentSubquery(in.input());
            case BoundColumnRefExpression ignored -> false;
            case BoundIntervalExpression ignored -> false;
            case BoundLiteralExpression ignored -> false;
            case BoundOutputColumnExpression ignored -> false;
            case BoundSubqueryExpression subquery -> canDecorrelateScalarAggregate(subquery);
        };
    }

    private boolean containsDependentSubquery(List<BoundExpression> expressions) {
        for (BoundExpression expression : expressions) {
            if (containsDependentSubquery(expression)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDependentSubquery(BoundCaseExpression caseExpression) {
        if (containsDependentSubquery(caseExpression.elseExpression())) {
            return true;
        }
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            if (containsDependentSubquery(branch.condition()) || containsDependentSubquery(branch.result())) {
                return true;
            }
        }
        return false;
    }

    private DependentRewrite rewriteExists(LogicalOperator root, BoundExistsSubqueryExpression exists) {
        if (!exists.isCorrelated()) {
            return new DependentRewrite(root, exists);
        }
        int markerOrdinal = outputColumnCount(root);
        ColumnCatalogEntry markerColumn = new ColumnCatalogEntry("#exists" + markerOrdinal, LogicalType.BOOLEAN, markerOrdinal);
        BoundColumnRefExpression marker = new BoundColumnRefExpression(markerColumn, markerOrdinal);
        return new DependentRewrite(new LogicalDependentJoin(root, exists, marker), marker);
    }

    private DependentRewrite rewriteScalar(LogicalOperator root, BoundSubqueryExpression subquery) {
        if (!canDecorrelateScalarAggregate(subquery)) {
            return new DependentRewrite(root, subquery);
        }
        int markerOrdinal = outputColumnCount(root);
        ColumnCatalogEntry markerColumn = new ColumnCatalogEntry("#scalar" + markerOrdinal, subquery.logicalType(), markerOrdinal);
        BoundColumnRefExpression marker = new BoundColumnRefExpression(markerColumn, markerOrdinal);
        return new DependentRewrite(LogicalDependentJoin.single(root, subquery, marker), marker);
    }

    private boolean canDecorrelateScalarAggregate(BoundSubqueryExpression subquery) {
        if (!subquery.isCorrelated()) {
            return false;
        }
        BoundSelectStatement statement = subquery.subquery();
        return statement.from() instanceof BoundTableRef
                && statement.isAggregateQuery()
                && statement.groupBy().isEmpty()
                && statement.having() == null
                && statement.orderBy().isEmpty()
                && statement.limit() == null
                && statement.selectList().size() == 1
                && decorrelatableAggregateOutput(statement.selectList().getFirst()) != null
                && decorrelatableCorrelations(subquery, statement.where());
    }

    private DependentRewrite rewriteBinary(LogicalOperator root, BoundBinaryExpression binary) {
        DependentRewrite left = rewriteDependentSubqueries(root, binary.left());
        DependentRewrite right = rewriteDependentSubqueries(left.root(), binary.right());
        return new DependentRewrite(
                right.root(),
                new BoundBinaryExpression(left.expression(), binary.operator(), right.expression(), binary.logicalType())
        );
    }

    private DependentRewrite rewriteBetween(LogicalOperator root, BoundBetweenExpression between) {
        DependentRewrite input = rewriteDependentSubqueries(root, between.input());
        DependentRewrite lower = rewriteDependentSubqueries(input.root(), between.lower());
        DependentRewrite upper = rewriteDependentSubqueries(lower.root(), between.upper());
        return new DependentRewrite(
                upper.root(),
                new BoundBetweenExpression(input.expression(), lower.expression(), upper.expression())
        );
    }

    private DependentRewrite rewriteCase(LogicalOperator root, BoundCaseExpression caseExpression) {
        DependentRewrite current = new DependentRewrite(root, null);
        ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            DependentRewrite condition = rewriteDependentSubqueries(current.root(), branch.condition());
            DependentRewrite result = rewriteDependentSubqueries(condition.root(), branch.result());
            branches.add(new BoundCaseExpression.WhenClause(condition.expression(), result.expression()));
            current = result;
        }
        DependentRewrite elseExpression = rewriteDependentSubqueries(current.root(), caseExpression.elseExpression());
        return new DependentRewrite(
                elseExpression.root(),
                new BoundCaseExpression(branches, elseExpression.expression(), caseExpression.logicalType())
        );
    }

    private DependentRewrite rewriteAggregate(LogicalOperator root, BoundAggregateExpression aggregate) {
        ExpressionListRewrite arguments = rewriteExpressions(root, aggregate.arguments());
        return new DependentRewrite(
                arguments.root(),
                new BoundAggregateExpression(
                        aggregate.function(),
                        arguments.expressions(),
                        aggregate.starArgument(),
                        aggregate.distinct()
                )
        );
    }

    private DependentRewrite rewriteFunction(LogicalOperator root, BoundFunctionExpression function) {
        ExpressionListRewrite arguments = rewriteExpressions(root, function.arguments());
        return new DependentRewrite(
                arguments.root(),
                new BoundFunctionExpression(function.function(), arguments.expressions())
        );
    }

    private DependentRewrite rewriteIn(LogicalOperator root, BoundInExpression in) {
        DependentRewrite input = rewriteDependentSubqueries(root, in.input());
        ExpressionListRewrite candidates = rewriteExpressions(input.root(), in.candidates());
        return new DependentRewrite(
                candidates.root(),
                new BoundInExpression(input.expression(), candidates.expressions(), in.negated())
        );
    }

    private ExpressionListRewrite rewriteExpressions(LogicalOperator root, List<BoundExpression> expressions) {
        LogicalOperator currentRoot = root;
        ArrayList<BoundExpression> rewritten = new ArrayList<>(expressions.size());
        for (BoundExpression expression : expressions) {
            DependentRewrite rewrite = rewriteDependentSubqueries(currentRoot, expression);
            currentRoot = rewrite.root();
            rewritten.add(rewrite.expression());
        }
        return new ExpressionListRewrite(currentRoot, rewritten);
    }

    private BoundAggregateExpression decorrelatableAggregateOutput(BoundExpression expression) {
        if (expression instanceof BoundAggregateExpression aggregate) {
            return aggregate.distinct() ? null : aggregate;
        }
        if (expression instanceof BoundBinaryExpression binary) {
            BoundAggregateExpression left = decorrelatableAggregateOutput(binary.left());
            BoundAggregateExpression right = decorrelatableAggregateOutput(binary.right());
            return combineAggregateCandidate(left, right);
        }
        if (expression instanceof BoundCastExpression cast) {
            return decorrelatableAggregateOutput(cast.child());
        }
        if (expression instanceof BoundLiteralExpression) {
            return null;
        }
        return null;
    }

    private BoundAggregateExpression combineAggregateCandidate(
            BoundAggregateExpression left,
            BoundAggregateExpression right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null || left.equals(right)) {
            return left;
        }
        return null;
    }

    private boolean decorrelatableCorrelations(BoundSubqueryExpression subquery, BoundExpression where) {
        ArrayList<BoundExpression> conjuncts = new ArrayList<>();
        flattenConjuncts(where, conjuncts);
        int equalityCount = 0;
        for (BoundExpression conjunct : conjuncts) {
            if (decorrelatableCorrelationEquality(subquery, conjunct)) {
                equalityCount++;
            } else if (containsOuterReference(subquery, conjunct)) {
                return false;
            }
        }
        return equalityCount > 0 && equalityCount <= 2;
    }

    private void flattenConjuncts(BoundExpression expression, List<BoundExpression> output) {
        if (expression == null) {
            return;
        }
        if (expression instanceof BoundBinaryExpression binary && binary.operator() == BinaryOperator.AND) {
            flattenConjuncts(binary.left(), output);
            flattenConjuncts(binary.right(), output);
            return;
        }
        output.add(expression);
    }

    private boolean decorrelatableCorrelationEquality(BoundSubqueryExpression subquery, BoundExpression expression) {
        if (!(expression instanceof BoundBinaryExpression binary) || binary.operator() != BinaryOperator.EQUAL) {
            return false;
        }
        if (!(binary.left() instanceof BoundColumnRefExpression left)
                || !(binary.right() instanceof BoundColumnRefExpression right)) {
            return false;
        }
        return decorrelatableCorrelationEquality(subquery, left, right)
                || decorrelatableCorrelationEquality(subquery, right, left);
    }

    private boolean decorrelatableCorrelationEquality(
            BoundSubqueryExpression subquery,
            BoundColumnRefExpression inner,
            BoundColumnRefExpression outer
    ) {
        if (inner.ordinal() >= subquery.localColumnCount() || outer.ordinal() < subquery.localColumnCount()) {
            return false;
        }
        if (!inner.logicalType().equals(outer.logicalType()) || !supportsScalarAggregateKeyType(inner.logicalType())) {
            return false;
        }
        int correlatedIndex = outer.ordinal() - subquery.localColumnCount();
        return correlatedIndex >= 0 && correlatedIndex < subquery.correlatedColumns().size();
    }

    private boolean containsOuterReference(BoundSubqueryExpression subquery, BoundExpression expression) {
        if (expression == null) {
            return false;
        }
        return switch (expression) {
            case BoundAggregateExpression aggregate -> containsOuterReference(subquery, aggregate.arguments());
            case BoundBetweenExpression between -> containsOuterReference(subquery, between.input())
                    || containsOuterReference(subquery, between.lower())
                    || containsOuterReference(subquery, between.upper());
            case BoundBinaryExpression binary -> containsOuterReference(subquery, binary.left())
                    || containsOuterReference(subquery, binary.right());
            case BoundCaseExpression caseExpression -> containsOuterReference(subquery, caseExpression);
            case BoundCastExpression cast -> containsOuterReference(subquery, cast.child());
            case BoundColumnRefExpression column -> column.ordinal() >= subquery.localColumnCount();
            case BoundFunctionExpression function -> containsOuterReference(subquery, function.arguments());
            case BoundInExpression in -> containsOuterReference(subquery, in.input())
                    || containsOuterReference(subquery, in.candidates());
            case BoundOutputColumnExpression output -> output.ordinal() >= subquery.localColumnCount();
            case BoundExistsSubqueryExpression ignored -> false;
            case BoundInSubqueryExpression in -> containsOuterReference(subquery, in.input());
            case BoundIntervalExpression ignored -> false;
            case BoundLiteralExpression ignored -> false;
            case BoundSubqueryExpression ignored -> false;
        };
    }

    private boolean containsOuterReference(BoundSubqueryExpression subquery, List<BoundExpression> expressions) {
        for (BoundExpression expression : expressions) {
            if (containsOuterReference(subquery, expression)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOuterReference(BoundSubqueryExpression subquery, BoundCaseExpression caseExpression) {
        if (containsOuterReference(subquery, caseExpression.elseExpression())) {
            return true;
        }
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            if (containsOuterReference(subquery, branch.condition())
                    || containsOuterReference(subquery, branch.result())) {
                return true;
            }
        }
        return false;
    }

    private boolean supportsScalarAggregateKeyType(LogicalType logicalType) {
        return logicalType.equals(LogicalType.BOOLEAN)
                || logicalType.equals(LogicalType.INTEGER)
                || logicalType.equals(LogicalType.BIGINT)
                || logicalType.equals(LogicalType.DATE);
    }

    private int outputColumnCount(LogicalOperator operator) {
        if (operator instanceof LogicalAggregate aggregate) {
            return aggregate.selectList().size();
        }
        if (operator instanceof LogicalDependentJoin join) {
            return outputColumnCount(join.child()) + 1;
        }
        if (operator instanceof LogicalFilter filter) {
            return outputColumnCount(filter.child());
        }
        if (operator instanceof LogicalGet get) {
            return columns(get.tableRef()).size();
        }
        if (operator instanceof LogicalJoin join) {
            return outputColumnCount(join.left()) + outputColumnCount(join.right());
        }
        if (operator instanceof LogicalLimit limit) {
            return outputColumnCount(limit.child());
        }
        if (operator instanceof LogicalOrder order) {
            return outputColumnCount(order.child());
        }
        if (operator instanceof LogicalProjection projection) {
            return projection.expressions().size();
        }
        throw new BinderException("Cannot derive output columns for " + operator.getClass().getSimpleName());
    }

    private List<ColumnCatalogEntry> columns(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().columns();
        }
        return tableRef.table().columns();
    }

}
