package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;

import java.util.List;

public final class PipelineExecutor {
    public void execute(Pipeline pipeline) {
        var sourceInput = new SourceInput(
                pipeline.sourceState(),
                pipeline.source().createLocalSourceState(pipeline.sourceState())
        );
        var operatorInputs = createOperatorInputs(pipeline);
        var sinkInput = new SinkInput(
                pipeline.sinkState(),
                pipeline.sink().createLocalSinkState(pipeline.sinkState())
        );

        pipeline.source().execute(sourceInput, chunk -> push(pipeline, operatorInputs, sinkInput, 0, chunk));
        pipeline.sink().combine(sinkInput.globalState(), sinkInput.localState());
    }

    private List<OperatorInput> createOperatorInputs(Pipeline pipeline) {
        var operators = pipeline.operators();
        var globalStates = pipeline.operatorStates();
        var inputs = new java.util.ArrayList<OperatorInput>(operators.size());
        for (int index = 0; index < operators.size(); index++) {
            var globalState = globalStates.get(index);
            inputs.add(new OperatorInput(globalState, operators.get(index).createLocalOperatorState(globalState)));
        }
        return inputs;
    }

    private void push(Pipeline pipeline, List<OperatorInput> operatorInputs, SinkInput sinkInput, int operatorIndex, DataChunk chunk) {
        if (operatorIndex >= pipeline.operators().size()) {
            pipeline.sink().sink(chunk, sinkInput);
            return;
        }
        var operator = pipeline.operators().get(operatorIndex);
        operator.execute(chunk, operatorInputs.get(operatorIndex), output -> push(pipeline, operatorInputs, sinkInput, operatorIndex + 1, output));
    }
}
