package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.ddl.TransactionalDdlExecutor;
import dev.trentdb.planner.logical.LogicalCreateIndex;
import java.util.List;

/** Catalog-only index creation; scans remain sequential until an access path is implemented. */
public final class PhysicalCreateIndex implements PhysicalSource {
    private final TransactionalDdlExecutor executor;
    private final LogicalCreateIndex logical;
    public PhysicalCreateIndex(TransactionalDdlExecutor executor, LogicalCreateIndex logical) { this.executor = executor; this.logical = logical; }
    @Override public PhysicalOperatorType type() { return PhysicalOperatorType.CREATE_INDEX; }
    @Override public void execute(PhysicalChunkConsumer consumer) { executor.createIndex(logical.statement().statement()); consumer.accept(DataChunk.empty(List.of())); }
}
