package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.ddl.TransactionalDdlExecutor;
import dev.trentdb.planner.logical.LogicalCreateTable;

import java.util.List;

public final class PhysicalCreateTable implements PhysicalSource {
    private final TransactionalDdlExecutor ddlExecutor;
    private final LogicalCreateTable logical;
    public PhysicalCreateTable(TransactionalDdlExecutor ddlExecutor, LogicalCreateTable logical) { this.ddlExecutor = ddlExecutor; this.logical = logical; }
    @Override public PhysicalOperatorType type() { return PhysicalOperatorType.CREATE_TABLE; }
    @Override public void execute(PhysicalChunkConsumer consumer) {
        ddlExecutor.createTable(logical.statement().statement());
        consumer.accept(DataChunk.empty(List.of()));
    }
}
