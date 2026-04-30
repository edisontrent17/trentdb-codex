package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundTableRef;

public record LogicalGet(BoundTableRef tableRef) implements LogicalOperator {
}
