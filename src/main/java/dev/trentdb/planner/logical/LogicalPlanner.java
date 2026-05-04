package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCaseExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundExpressionTypes;
import dev.trentdb.planner.BoundExplainStatement;
import dev.trentdb.planner.BoundFrom;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundJoinRef;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.BoundStatement;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.planner.BinderException;

import java.util.ArrayList;
import java.util.List;

public final class LogicalPlanner {
    private record AggregatePlan(
            List<BoundExpression> aggregateOutputs,
            List<String> aggregateNames,
            List<BoundExpression> projections
    ) {
        private AggregatePlan {
            aggregateOutputs = List.copyOf(aggregateOutputs);
            aggregateNames = List.copyOf(aggregateNames);
            projections = List.copyOf(projections);
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
            root = new LogicalFilter(statement.where(), root);
        }
        if (statement.isAggregateQuery()) {
            AggregatePlan aggregatePlan = aggregatePlan(statement);
            root = new LogicalAggregate(
                    statement.groupBy(),
                    aggregatePlan.aggregateOutputs(),
                    aggregatePlan.aggregateNames(),
                    root
            );
            root = new LogicalProjection(aggregatePlan.projections(), statement.selectNames(), root);
            if (!statement.orderBy().isEmpty()) {
                root = new LogicalOrder(statement.orderBy(), root);
            }
        } else {
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

    private LogicalOperator planFrom(BoundFrom from) {
        if (from instanceof BoundTableRef tableRef) {
            return new LogicalGet(tableRef);
        }
        if (from instanceof BoundJoinRef joinRef) {
            return new LogicalJoin(joinRef.left(), joinRef.right(), joinRef.condition());
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
        return new AggregatePlan(outputs, names, projections);
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
            case BoundIntervalExpression interval -> interval;
            case BoundLiteralExpression literal -> literal;
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
}
