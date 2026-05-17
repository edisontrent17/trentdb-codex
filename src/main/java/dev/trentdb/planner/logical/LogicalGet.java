package dev.trentdb.planner.logical;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.planner.BoundTableRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public record LogicalGet(BoundTableRef tableRef, List<Integer> projectedOrdinals) implements LogicalOperator {
    public LogicalGet(BoundTableRef tableRef) {
        this(tableRef, allOrdinals(tableRef));
    }

    public LogicalGet {
        projectedOrdinals = List.copyOf(projectedOrdinals);
        validateProjectedOrdinals(tableRef, projectedOrdinals);
    }

    public List<ColumnCatalogEntry> tableColumns() {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().columns();
        }
        return tableRef.table().columns();
    }

    public List<ColumnCatalogEntry> projectedColumns() {
        List<ColumnCatalogEntry> tableColumns = tableColumns();
        ArrayList<ColumnCatalogEntry> columns = new ArrayList<>(projectedOrdinals.size());
        for (int index = 0; index < projectedOrdinals.size(); index++) {
            ColumnCatalogEntry column = tableColumns.get(projectedOrdinals.get(index));
            columns.add(new ColumnCatalogEntry(column.name(), column.logicalType(), index));
        }
        return List.copyOf(columns);
    }

    public boolean projectsAllColumns() {
        if (projectedOrdinals.size() != tableColumns().size()) {
            return false;
        }
        for (int index = 0; index < projectedOrdinals.size(); index++) {
            if (projectedOrdinals.get(index) != index) {
                return false;
            }
        }
        return true;
    }

    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_GET;
    }

    private static List<Integer> allOrdinals(BoundTableRef tableRef) {
        List<ColumnCatalogEntry> columns = tableRef.isReplacementScan()
                ? tableRef.replacementScan().columns()
                : tableRef.table().columns();
        ArrayList<Integer> ordinals = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            ordinals.add(index);
        }
        return List.copyOf(ordinals);
    }

    private static void validateProjectedOrdinals(BoundTableRef tableRef, List<Integer> projectedOrdinals) {
        if (projectedOrdinals.isEmpty()) {
            throw new IllegalArgumentException("LogicalGet must project at least one column");
        }
        int columnCount = allOrdinals(tableRef).size();
        HashSet<Integer> seen = new HashSet<>();
        for (Integer ordinal : projectedOrdinals) {
            if (ordinal == null || ordinal < 0 || ordinal >= columnCount) {
                throw new IllegalArgumentException("Projected column ordinal is outside the table schema");
            }
            if (!seen.add(ordinal)) {
                throw new IllegalArgumentException("Projected column ordinal must be unique");
            }
        }
    }
}
