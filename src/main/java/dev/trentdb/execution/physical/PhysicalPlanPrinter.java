package dev.trentdb.execution.physical;

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
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.planner.BoundTableRef;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalPlanPrinter {
    public String print(Pipeline pipeline) {
        StringBuilder builder = new StringBuilder();
        builder.append("Physical Plan\n");
        appendSource(builder, pipeline.source(), 1);
        if (!pipeline.operators().isEmpty()) {
            appendLine(builder, 1, "Operators");
            for (PhysicalOperator operator : pipeline.operators()) {
                appendOperator(builder, operator, 2);
            }
        }
        appendLine(builder, 1, "Sink: " + pipeline.sink().type().name());
        return builder.toString();
    }

    private void appendSource(StringBuilder builder, PhysicalSource source, int depth) {
        if (source instanceof PhysicalTableScan scan) {
            appendLine(builder, depth, "Source: PhysicalTableScan table=" + tableName(scan.tableRef()));
            return;
        }
        appendLine(builder, depth, "Source: " + source.type().name());
    }

    private void appendOperator(StringBuilder builder, PhysicalOperator operator, int depth) {
        if (operator instanceof PhysicalFilter filter) {
            appendLine(builder, depth, "PhysicalFilter predicate=" + expression(filter.predicate()));
            return;
        }
        if (operator instanceof PhysicalProjection projection) {
            appendLine(builder, depth, "PhysicalProjection expressions=[" + projection.expressions().size() + "]");
            return;
        }
        if (operator instanceof PhysicalHashAggregate aggregate) {
            String mode = aggregate.groups().isEmpty() ? "ungrouped" : "grouped";
            appendLine(builder, depth, "PhysicalHashAggregate mode=" + mode
                    + " groups=[" + aggregate.groups().size() + "]"
                    + " expressions=[" + aggregate.selectList().size() + "]");
            return;
        }
        if (operator instanceof PhysicalHashJoin join) {
            appendLine(builder, depth, "PhysicalHashJoin right=" + tableName(join.right())
                    + " type=" + join.joinType().name()
                    + " leftKeyOrdinal=" + join.leftKeyOrdinal()
                    + " rightKeyOrdinal=" + join.rightKeyOrdinal());
            appendOptionalExpression(builder, depth + 1, "rightFilter", join.rightFilter());
            appendOptionalExpression(builder, depth + 1, "residualFilter", join.residualFilter());
            return;
        }
        if (operator instanceof PhysicalCorrelatedExistsMarkJoin join) {
            appendLine(builder, depth, "PhysicalMarkJoin subquery=EXISTS marker="
                    + join.marker().name() + "#" + join.marker().ordinal());
            return;
        }
        if (operator instanceof PhysicalCorrelatedScalarAggregateJoin join) {
            appendLine(builder, depth, "PhysicalSingleJoin subquery=SCALAR marker="
                    + join.marker().name() + "#" + join.marker().ordinal());
            return;
        }
        if (operator instanceof PhysicalNestedLoopJoin join) {
            appendLine(builder, depth, "PhysicalNestedLoopJoin right=" + tableName(join.right())
                    + " type=" + join.joinType().name());
            appendOptionalExpression(builder, depth + 1, "condition", join.condition());
            appendOptionalExpression(builder, depth + 1, "rightFilter", join.rightFilter());
            return;
        }
        if (operator instanceof PhysicalOrder order) {
            appendLine(builder, depth, "PhysicalOrder orders=[" + order.orders().size() + "]");
            return;
        }
        if (operator instanceof PhysicalLimit limit) {
            appendLine(builder, depth, "PhysicalLimit " + limit.limit());
            return;
        }
        appendLine(builder, depth, operator.type().name());
    }

    private void appendOptionalExpression(StringBuilder builder, int depth, String label, BoundExpression expression) {
        if (expression != null) {
            appendLine(builder, depth, label + "=" + expression(expression));
        }
    }

    private String tableName(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().path();
        }
        return tableRef.table().name();
    }

    private String expression(BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> aggregate.name() + "("
                    + (aggregate.distinct() ? "DISTINCT " : "")
                    + (aggregate.starArgument() ? "*" : expressions(aggregate.arguments())) + ")";
            case BoundBetweenExpression between -> "(" + expression(between.input())
                    + " BETWEEN " + expression(between.lower()) + " AND " + expression(between.upper()) + ")";
            case BoundBinaryExpression binary -> "(" + expression(binary.left())
                    + " " + binary.operator().name() + " " + expression(binary.right()) + ")";
            case BoundCaseExpression caseExpression -> caseExpression(caseExpression);
            case BoundCastExpression cast -> "CAST(" + expression(cast.child()) + " AS " + cast.logicalType().id().name() + ")";
            case BoundColumnRefExpression column -> column.name() + "#" + column.ordinal();
            case BoundFunctionExpression function -> function.name() + "(" + expressions(function.arguments()) + ")";
            case BoundInExpression in -> expression(in.input()) + (in.negated() ? " NOT IN " : " IN ")
                    + "(" + expressions(in.candidates()) + ")";
            case BoundInSubqueryExpression in -> expression(in.input())
                    + (in.negated() ? " NOT IN " : " IN ") + "(SUBQUERY)";
            case BoundExistsSubqueryExpression ignored -> "EXISTS (SUBQUERY)";
            case BoundIntervalExpression interval -> "INTERVAL '" + interval.amount() + "' " + interval.unit().name();
            case BoundLiteralExpression literal -> literal.value() == null ? "NULL" : literal.value().toString();
            case BoundOutputColumnExpression output -> output.name() + "#" + output.ordinal();
            case BoundSubqueryExpression ignored -> "(SUBQUERY)";
        };
    }

    private String caseExpression(BoundCaseExpression caseExpression) {
        StringBuilder builder = new StringBuilder("CASE");
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            builder.append(" WHEN ").append(expression(branch.condition()));
            builder.append(" THEN ").append(expression(branch.result()));
        }
        builder.append(" ELSE ").append(expression(caseExpression.elseExpression()));
        builder.append(" END");
        return builder.toString();
    }

    private String expressions(List<BoundExpression> expressions) {
        ArrayList<String> printed = new ArrayList<>(expressions.size());
        for (BoundExpression expression : expressions) {
            printed.add(expression(expression));
        }
        return String.join(", ", printed);
    }

    private void appendLine(StringBuilder builder, int depth, String line) {
        builder.append("  ".repeat(depth)).append(line).append("\n");
    }
}
