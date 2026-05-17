package dev.trentdb.planner.logical;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.planner.BoundExpressionPrinter;
import dev.trentdb.planner.BoundOrderByItem;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.planner.PlanTreeRenderer;
import dev.trentdb.planner.PlanTreeRenderer.Entry;
import dev.trentdb.planner.PlanTreeRenderer.Node;

import java.util.ArrayList;
import java.util.List;

public final class LogicalPlanPrinter {
    private final PlanTreeRenderer renderer = new PlanTreeRenderer();
    private final BoundExpressionPrinter expressionPrinter = new BoundExpressionPrinter();

    public String print(LogicalOperator operator) {
        return renderer.print(node(operator));
    }

    private Node node(LogicalOperator operator) {
        return switch (operator) {
            case LogicalAggregate aggregate -> Node.of(
                    "AGGREGATE",
                    aggregateEntries(aggregate),
                    List.of(node(aggregate.child()))
            );
            case LogicalExplain explain -> Node.of("EXPLAIN", List.of(), List.of(node(explain.child())));
            case LogicalDependentJoin join -> Node.of(
                    "DELIM_JOIN",
                    dependentJoinEntries(join),
                    List.of(node(join.child()))
            );
            case LogicalProjection projection -> Node.of(
                    "PROJECTION",
                    List.of(Entry.of("Expressions", expressionPrinter.printListValues(projection.expressions()))),
                    List.of(node(projection.child()))
            );
            case LogicalJoin join -> Node.of(
                    "COMPARISON_JOIN",
                    comparisonJoinEntries(join),
                    List.of(node(join.left()), node(join.right()))
            );
            case LogicalFilter filter -> Node.of(
                    "FILTER",
                    List.of(Entry.of("Expressions", expressionPrinter.print(filter.predicate()))),
                    List.of(node(filter.child()))
            );
            case LogicalLimit limit -> Node.of(
                    "LIMIT",
                    List.of(Entry.of("Limit", Long.toString(limit.limit()))),
                    List.of(node(limit.child()))
            );
            case LogicalOrder order -> Node.of(
                    "ORDER_BY",
                    List.of(Entry.of("Order By", orderEntries(order.orders()))),
                    List.of(node(order.child()))
            );
            case LogicalGet get -> getNode(get);
        };
    }

    private List<Entry> aggregateEntries(LogicalAggregate aggregate) {
        ArrayList<Entry> entries = new ArrayList<>();
        if (!aggregate.groups().isEmpty()) {
            entries.add(Entry.of("Groups", expressionPrinter.printListValues(aggregate.groups())));
        }
        entries.add(Entry.of("Expressions", expressionPrinter.printListValues(aggregate.selectList())));
        return List.copyOf(entries);
    }

    private List<Entry> comparisonJoinEntries(LogicalJoin join) {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(Entry.of("Join Type", join.joinType().name()));
        if (join.condition() != null) {
            entries.add(Entry.of("Conditions", expressionPrinter.print(join.condition())));
        }
        return List.copyOf(entries);
    }

    private List<Entry> dependentJoinEntries(LogicalDependentJoin join) {
        ArrayList<Entry> entries = new ArrayList<>();
        entries.add(Entry.of("Join Type", join.kind().name()));
        entries.add(Entry.of("Marker", join.marker().name() + "#" + join.marker().ordinal()));
        if (join.kind() == LogicalDependentJoin.Kind.MARK) {
            entries.add(Entry.of("Subquery", "EXISTS"));
        } else {
            entries.add(Entry.of("Subquery", "SCALAR"));
        }
        return List.copyOf(entries);
    }

    private List<String> orderEntries(List<BoundOrderByItem> orders) {
        ArrayList<String> values = new ArrayList<>(orders.size());
        for (BoundOrderByItem order : orders) {
            values.add(expressionPrinter.print(order.expression()) + " " + order.direction().name());
        }
        return List.copyOf(values);
    }

    private Node getNode(LogicalGet get) {
        BoundTableRef tableRef = get.tableRef();
        List<Entry> entries = getEntries(get);
        if (tableRef.isReplacementScan()) {
            return Node.of(
                    "READ_CSV_AUTO",
                    entries,
                    List.of()
            );
        }
        return Node.of("SEQ_SCAN", entries, List.of());
    }

    private List<Entry> getEntries(LogicalGet get) {
        ArrayList<Entry> entries = new ArrayList<>();
        BoundTableRef tableRef = get.tableRef();
        if (tableRef.isReplacementScan()) {
            entries.add(Entry.of("Path", tableRef.replacementScan().path()));
        } else {
            entries.add(Entry.of("Table", tableRef.table().name()));
        }
        entries.add(Entry.of("Columns", projectedColumns(get)));
        return List.copyOf(entries);
    }

    private List<String> projectedColumns(LogicalGet get) {
        ArrayList<String> columns = new ArrayList<>(get.projectedOrdinals().size());
        List<ColumnCatalogEntry> tableColumns = get.tableColumns();
        for (Integer ordinal : get.projectedOrdinals()) {
            columns.add(tableColumns.get(ordinal).name());
        }
        return List.copyOf(columns);
    }
}
