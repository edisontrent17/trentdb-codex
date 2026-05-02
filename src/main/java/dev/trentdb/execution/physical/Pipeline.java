package dev.trentdb.execution.physical;

import java.util.List;

public record Pipeline(
        PhysicalSource source,
        List<PhysicalOperator> operators,
        PhysicalSink sink,
        GlobalSourceState sourceState,
        List<GlobalOperatorState> operatorStates,
        GlobalSinkState sinkState
) {
    public Pipeline {
        operators = List.copyOf(operators);
        operatorStates = List.copyOf(operatorStates);
    }

    public Pipeline(PhysicalSource source, List<PhysicalOperator> operators, PhysicalSink sink) {
        this(
                source,
                operators,
                sink,
                source.createGlobalSourceState(),
                operators.stream().map(PhysicalOperator::createGlobalOperatorState).toList(),
                sink.createGlobalSinkState()
        );
    }
}
