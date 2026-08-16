package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.ddl.TransactionalDdlExecutor;
import dev.trentdb.planner.logical.LogicalInsert;
import java.util.List;

public final class PhysicalInsert implements PhysicalSource {
    private final TransactionalDdlExecutor executor;
    private final LogicalInsert logical;
    public PhysicalInsert(TransactionalDdlExecutor executor, LogicalInsert logical) { this.executor = executor; this.logical = logical; }
    @Override public PhysicalOperatorType type() { return PhysicalOperatorType.INSERT; }
    @Override public void execute(PhysicalChunkConsumer consumer) {
        executor.insert(logical.statement());
        consumer.accept(DataChunk.empty(List.of()));
    }
}
