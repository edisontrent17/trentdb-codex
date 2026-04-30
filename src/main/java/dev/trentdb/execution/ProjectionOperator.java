package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;

import java.util.List;

final class ProjectionOperator implements ChunkConsumer {
    private final List<BoundExpression> expressions;
    private final ChunkConsumer downstream;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    ProjectionOperator(List<BoundExpression> expressions, ChunkConsumer downstream) {
        this.expressions = List.copyOf(expressions);
        this.downstream = downstream;
    }

    @Override
    public void accept(DataChunk chunk) {
        var names = expressions.stream().map(this::name).toList();
        var vectors = expressions.stream().map(expression -> expressionExecutor.execute(expression, chunk)).toList();
        downstream.accept(new DataChunk(names, vectors));
    }

    private String name(BoundExpression expression) {
        return switch (expression) {
            case BoundColumnRefExpression column -> column.name();
            case BoundFunctionExpression function -> function.name();
            default -> "?column?";
        };
    }
}
