package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.ddl.TransactionalDdlExecutor;
import dev.trentdb.planner.logical.LogicalDropTable;

import java.util.List;

public final class PhysicalDropTable implements PhysicalSource {
    private final TransactionalDdlExecutor ddlExecutor;
    private final LogicalDropTable logical;
    public PhysicalDropTable(TransactionalDdlExecutor ddlExecutor, LogicalDropTable logical) { this.ddlExecutor = ddlExecutor; this.logical = logical; }
    @Override public PhysicalOperatorType type() { return PhysicalOperatorType.DROP_TABLE; }
    @Override public void execute(PhysicalChunkConsumer consumer) {
        ddlExecutor.dropTable(logical.statement().statement());
        consumer.accept(DataChunk.empty(List.of()));
    }
}
