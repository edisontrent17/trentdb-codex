package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.ddl.TransactionalDdlExecutor;
import dev.trentdb.planner.logical.LogicalUpdate;

import java.util.List;

public final class PhysicalUpdate implements PhysicalSource {
    private final TransactionalDdlExecutor executor;
    private final LogicalUpdate logical;
    public PhysicalUpdate(TransactionalDdlExecutor executor, LogicalUpdate logical) { this.executor = executor; this.logical = logical; }
    @Override public PhysicalOperatorType type() { return PhysicalOperatorType.UPDATE; }
    @Override public void execute(PhysicalChunkConsumer consumer) { executor.update(logical.statement()); consumer.accept(DataChunk.empty(List.of())); }
}
