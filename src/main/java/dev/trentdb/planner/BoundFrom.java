package dev.trentdb.planner;

public sealed interface BoundFrom permits BoundJoinRef, BoundSubqueryRef, BoundTableRef {
}
