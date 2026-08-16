package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.ddl.TransactionalDdlExecutor;
import dev.trentdb.planner.logical.LogicalDropIndex;
import java.util.List;

public final class PhysicalDropIndex implements PhysicalSource {
    private final TransactionalDdlExecutor executor;
    private final LogicalDropIndex logical;
    public PhysicalDropIndex(TransactionalDdlExecutor executor, LogicalDropIndex logical) { this.executor = executor; this.logical = logical; }
    @Override public PhysicalOperatorType type() { return PhysicalOperatorType.DROP_INDEX; }
    @Override public void execute(PhysicalChunkConsumer consumer) { executor.dropIndex(logical.statement().statement()); consumer.accept(DataChunk.empty(List.of())); }
}
