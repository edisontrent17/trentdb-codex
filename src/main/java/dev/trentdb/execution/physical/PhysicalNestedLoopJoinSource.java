package dev.trentdb.execution.physical;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalNestedLoopJoinSource implements PhysicalSource {
    private final StorageManager storageManager;
    private final BoundTableRef left;
    private final BoundTableRef right;
    private final BoundExpression condition;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    public PhysicalNestedLoopJoinSource(
            StorageManager storageManager,
            BoundTableRef left,
            BoundTableRef right,
            BoundExpression condition
    ) {
        this.storageManager = storageManager;
        this.left = left;
        this.right = right;
        this.condition = condition;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.NESTED_LOOP_JOIN;
    }

    @Override
    public void execute(PhysicalChunkConsumer consumer) {
        List<ColumnCatalogEntry> leftColumns = columns(left);
        List<ColumnCatalogEntry> rightColumns = columns(right);
        List<String> outputNames = outputNames(leftColumns, rightColumns);
        List<LogicalType> outputTypes = outputTypes(leftColumns, rightColumns);
        List<DataChunk> leftChunks = scanChunks(left);
        List<DataChunk> rightChunks = scanChunks(right);
        ArrayList<Object[]> bufferedRows = new ArrayList<>(InMemoryTableStorage.STANDARD_VECTOR_SIZE);

        for (DataChunk leftChunk : leftChunks) {
            for (DataChunk rightChunk : rightChunks) {
                for (int leftIndex = 0; leftIndex < leftChunk.cardinality(); leftIndex++) {
                    for (int rightIndex = 0; rightIndex < rightChunk.cardinality(); rightIndex++) {
                        Object[] joinedRow = joinedRow(leftChunk, leftIndex, rightChunk, rightIndex);
                        if (matches(joinedRow, outputNames, outputTypes)) {
                            bufferedRows.add(joinedRow);
                            if (bufferedRows.size() >= InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
                                consumer.accept(chunk(outputNames, outputTypes, bufferedRows));
                                bufferedRows.clear();
                            }
                        }
                    }
                }
            }
        }

        if (!bufferedRows.isEmpty()) {
            consumer.accept(chunk(outputNames, outputTypes, bufferedRows));
        }
    }

    private List<DataChunk> scanChunks(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().scanFunction().scan();
        }
        return storageManager.getTable(tableRef.table()).scanChunks();
    }

    private List<ColumnCatalogEntry> columns(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().columns();
        }
        return tableRef.table().columns();
    }

    private List<String> outputNames(List<ColumnCatalogEntry> leftColumns, List<ColumnCatalogEntry> rightColumns) {
        ArrayList<String> names = new ArrayList<>(leftColumns.size() + rightColumns.size());
        for (ColumnCatalogEntry column : leftColumns) {
            names.add(column.name());
        }
        for (ColumnCatalogEntry column : rightColumns) {
            names.add(column.name());
        }
        return names;
    }

    private List<LogicalType> outputTypes(List<ColumnCatalogEntry> leftColumns, List<ColumnCatalogEntry> rightColumns) {
        ArrayList<LogicalType> types = new ArrayList<>(leftColumns.size() + rightColumns.size());
        for (ColumnCatalogEntry column : leftColumns) {
            types.add(column.logicalType());
        }
        for (ColumnCatalogEntry column : rightColumns) {
            types.add(column.logicalType());
        }
        return types;
    }

    private Object[] joinedRow(DataChunk leftChunk, int leftIndex, DataChunk rightChunk, int rightIndex) {
        Object[] row = new Object[leftChunk.vectors().size() + rightChunk.vectors().size()];
        for (int columnIndex = 0; columnIndex < leftChunk.vectors().size(); columnIndex++) {
            row[columnIndex] = leftChunk.column(columnIndex).get(leftIndex);
        }
        int rightOffset = leftChunk.vectors().size();
        for (int columnIndex = 0; columnIndex < rightChunk.vectors().size(); columnIndex++) {
            row[rightOffset + columnIndex] = rightChunk.column(columnIndex).get(rightIndex);
        }
        return row;
    }

    private boolean matches(Object[] joinedRow, List<String> names, List<LogicalType> types) {
        DataChunk rowChunk = singleRowChunk(joinedRow, names, types);
        Object value = expressionExecutor.execute(condition, rowChunk).get(0);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean bool)) {
            throw new ExecutionException("Join condition must evaluate to BOOLEAN");
        }
        return bool;
    }

    private DataChunk singleRowChunk(Object[] row, List<String> names, List<LogicalType> types) {
        ArrayList<Vector> vectors = new ArrayList<>(row.length);
        for (int columnIndex = 0; columnIndex < row.length; columnIndex++) {
            Vector vector = new Vector(types.get(columnIndex), 1);
            vector.set(0, row[columnIndex]);
            vectors.add(vector);
        }
        return new DataChunk(names, vectors);
    }

    private DataChunk chunk(List<String> names, List<LogicalType> types, List<Object[]> rows) {
        int cardinality = rows.size();
        ArrayList<Vector> vectors = new ArrayList<>(names.size());
        for (int columnIndex = 0; columnIndex < names.size(); columnIndex++) {
            Vector vector = new Vector(types.get(columnIndex), cardinality);
            for (int rowIndex = 0; rowIndex < cardinality; rowIndex++) {
                vector.set(rowIndex, rows.get(rowIndex)[columnIndex]);
            }
            vectors.add(vector);
        }
        return new DataChunk(names, vectors);
    }
}
