package dev.trentdb.execution.physical;

import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundExpressionPrinter;
import dev.trentdb.planner.BoundOrderByItem;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.planner.PlanTreeRenderer;
import dev.trentdb.planner.PlanTreeRenderer.Entry;
import dev.trentdb.planner.PlanTreeRenderer.Node;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalPlanPrinter {
    private final PlanTreeRenderer renderer = new PlanTreeRenderer();
    private final BoundExpressionPrinter expressionPrinter = new BoundExpressionPrinter();

    public String print(Pipeline pipeline) {
        Node current = sourceNode(pipeline.source());
        for (PhysicalOperator operator : pipeline.operators()) {
            current = operatorNode(operator, current);
        }
        return renderer.print(current);
    }

    private Node sourceNode(PhysicalSource source) {
        if (source instanceof PhysicalTableScan scan) {
            return scanNode(scan.tableRef(), scan.projectedOrdinals());
        }
        return Node.leaf(source.type().name());
    }

    private Node operatorNode(PhysicalOperator operator, Node child) {
        if (operator instanceof PhysicalFilter filter) {
            return Node.of(
                    "FILTER",
                    List.of(Entry.of("Expression", expressionPrinter.print(filter.predicate()))),
                    List.of(child)
            );
        }
        if (operator instanceof PhysicalProjection projection) {
            return Node.of(
                    "PROJECTION",
                    List.of(Entry.of("Projections", expressionPrinter.printListValues(projection.expressions()))),
                    List.of(child)
            );
        }
        if (operator instanceof PhysicalHashAggregate aggregate) {
            String name = aggregate.groups().isEmpty() ? "UNGROUPED_AGGREGATE" : "HASH_GROUP_BY";
            return Node.of(name, aggregateEntries(aggregate), List.of(child));
        }
        if (operator instanceof PhysicalHashJoin join) {
            return Node.of(
                    "HASH_JOIN",
                    hashJoinEntries(join),
                    List.of(child, scanNode(join.right(), join.rightProjectedOrdinals()))
            );
        }
        if (operator instanceof PhysicalCorrelatedExistsMarkJoin join) {
            return Node.of("MARK_JOIN", markJoinEntries(join), List.of(child));
        }
        if (operator instanceof PhysicalCorrelatedScalarAggregateJoin join) {
            return Node.of("SINGLE_JOIN", singleJoinEntries(join), List.of(child));
        }
        if (operator instanceof PhysicalNestedLoopJoin join) {
            return Node.of(
                    "NESTED_LOOP_JOIN",
                    nestedLoopJoinEntries(join),
                    List.of(child, scanNode(join.right(), join.rightProjectedOrdinals()))
            );
        }
        if (operator instanceof PhysicalOrder order) {
            return Node.of(
                    "ORDER_BY",
                    List.of(Entry.of("Order By", orderEntries(order.orders()))),
                    List.of(child)
            );
        }
        if (operator instanceof PhysicalLimit limit) {
            return Node.of(
                    "STREAMING_LIMIT",
                    List.of(Entry.of("Limit", Long.toString(limit.limit()))),
                    List.of(child)
            );
        }
        return Node.of(operator.type().name(), List.of(), List.of(child));
    }

    private List<Entry> aggregateEntries(PhysicalHashAggregate aggregate) {
        ArrayList<Entry> entries = new ArrayList<>();
        if (!aggregate.groups().isEmpty()) {
            entries.add(Entry.of("Groups", expressionPrinter.printListValues(aggregate.groups())));
        }
        entries.add(Entry.of("Aggregates", expressionPrinter.printListValues(aggregate.selectList())));
        return List.copyOf(entries);
    }

    private List<Entry> hashJoinEntries(PhysicalHashJoin join) {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(Entry.of("Join Type", join.joinType().name()));
        entries.add(Entry.of(
                "Conditions",
                "left[" + join.leftKeyOrdinal() + "] = right[" + join.rightKeyOrdinal() + "]"
        ));
        appendOptionalExpression(entries, "Right Filter", join.rightFilter());
        appendOptionalExpression(entries, "Residual", join.residualFilter());
        return List.copyOf(entries);
    }

    private List<Entry> markJoinEntries(PhysicalCorrelatedExistsMarkJoin join) {
        return List.of(
                Entry.of("Join Type", "MARK"),
                Entry.of("Subquery", "EXISTS"),
                Entry.of("Marker", join.marker().name() + "#" + join.marker().ordinal())
        );
    }

    private List<Entry> singleJoinEntries(PhysicalCorrelatedScalarAggregateJoin join) {
        return List.of(
                Entry.of("Join Type", "SINGLE"),
                Entry.of("Subquery", "SCALAR"),
                Entry.of("Marker", join.marker().name() + "#" + join.marker().ordinal())
        );
    }

    private List<Entry> nestedLoopJoinEntries(PhysicalNestedLoopJoin join) {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(Entry.of("Join Type", join.joinType().name()));
        appendOptionalExpression(entries, "Conditions", join.condition());
        appendOptionalExpression(entries, "Right Filter", join.rightFilter());
        return List.copyOf(entries);
    }

    private void appendOptionalExpression(ArrayList<Entry> entries, String label, BoundExpression expression) {
        if (expression != null) {
            entries.add(Entry.of(label, expressionPrinter.print(expression)));
        }
    }

    private List<String> orderEntries(List<BoundOrderByItem> orders) {
        ArrayList<String> values = new ArrayList<>(orders.size());
        for (BoundOrderByItem order : orders) {
            values.add(expressionPrinter.print(order.expression()) + " " + order.direction().name());
        }
        return List.copyOf(values);
    }

    private Node scanNode(BoundTableRef tableRef, List<Integer> projectedOrdinals) {
        List<Entry> entries = scanEntries(tableRef, projectedOrdinals);
        if (tableRef.isReplacementScan()) {
            return Node.of(
                    "READ_CSV_AUTO",
                    entries,
                    List.of()
            );
        }
        return Node.of("SEQ_SCAN", entries, List.of());
    }

    private List<Entry> scanEntries(BoundTableRef tableRef, List<Integer> projectedOrdinals) {
        ArrayList<Entry> entries = new ArrayList<>();
        if (tableRef.isReplacementScan()) {
            entries.add(Entry.of("Path", tableRef.replacementScan().path()));
        } else {
            entries.add(Entry.of("Table", tableRef.table().name()));
        }
        entries.add(Entry.of("Columns", projectedColumns(tableRef, projectedOrdinals)));
        return List.copyOf(entries);
    }

    private List<String> projectedColumns(BoundTableRef tableRef, List<Integer> projectedOrdinals) {
        ArrayList<String> columns = new ArrayList<>(projectedOrdinals.size());
        for (Integer ordinal : projectedOrdinals) {
            String name = tableRef.isReplacementScan()
                    ? tableRef.replacementScan().columns().get(ordinal).name()
                    : tableRef.table().columns().get(ordinal).name();
            columns.add(name);
        }
        return List.copyOf(columns);
    }
}
