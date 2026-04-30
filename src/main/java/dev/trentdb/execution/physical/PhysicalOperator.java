package dev.trentdb.execution.physical;

public sealed interface PhysicalOperator permits PhysicalIntermediateOperator, PhysicalSink, PhysicalSource {
}
