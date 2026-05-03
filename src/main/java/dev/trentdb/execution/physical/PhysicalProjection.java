package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundExpression;

import java.util.List;

public final class PhysicalProjection implements PhysicalOperator {
    private final List<BoundExpression> expressions;
    private final List<String> names;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    public PhysicalProjection(List<BoundExpression> expressions, List<String> names) {
        this.expressions = List.copyOf(expressions);
        this.names = List.copyOf(names);
    }

    public List<BoundExpression> expressions() {
        return expressions;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.PROJECTION;
    }

    @Override
    public void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        List<Vector> vectors = expressions.stream().map(expression -> expressionExecutor.execute(expression, input)).toList();
        downstream.accept(new DataChunk(names, vectors));
    }
}
