package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;

import java.util.ArrayList;
import java.util.List;

public final class PipelineExecutor {
    public void execute(Pipeline pipeline) {
        SourceInput sourceInput = new SourceInput(
                pipeline.sourceState(),
                pipeline.source().createLocalSourceState(pipeline.sourceState())
        );
        List<OperatorInput> operatorInputs = createOperatorInputs(pipeline);
        SinkInput sinkInput = new SinkInput(
                pipeline.sinkState(),
                pipeline.sink().createLocalSinkState(pipeline.sinkState())
        );

        pipeline.source().execute(sourceInput, chunk -> push(pipeline, operatorInputs, sinkInput, 0, chunk));
        finish(pipeline, operatorInputs, sinkInput, 0);
        pipeline.sink().combine(sinkInput.globalState(), sinkInput.localState());
    }

    private List<OperatorInput> createOperatorInputs(Pipeline pipeline) {
        List<PhysicalOperator> operators = pipeline.operators();
        List<GlobalOperatorState> globalStates = pipeline.operatorStates();
        ArrayList<OperatorInput> inputs = new ArrayList<>(operators.size());
        for (int index = 0; index < operators.size(); index++) {
            GlobalOperatorState globalState = globalStates.get(index);
            inputs.add(new OperatorInput(globalState, operators.get(index).createLocalOperatorState(globalState)));
        }
        return inputs;
    }

    private void push(Pipeline pipeline, List<OperatorInput> operatorInputs, SinkInput sinkInput, int operatorIndex, DataChunk chunk) {
        if (operatorIndex >= pipeline.operators().size()) {
            pipeline.sink().sink(chunk, sinkInput);
            return;
        }
        PhysicalOperator operator = pipeline.operators().get(operatorIndex);
        operator.execute(chunk, operatorInputs.get(operatorIndex), output -> push(pipeline, operatorInputs, sinkInput, operatorIndex + 1, output));
    }

    private void finish(Pipeline pipeline, List<OperatorInput> operatorInputs, SinkInput sinkInput, int operatorIndex) {
        if (operatorIndex >= pipeline.operators().size()) {
            return;
        }
        PhysicalOperator operator = pipeline.operators().get(operatorIndex);
        operator.finish(operatorInputs.get(operatorIndex), output -> push(pipeline, operatorInputs, sinkInput, operatorIndex + 1, output));
        finish(pipeline, operatorInputs, sinkInput, operatorIndex + 1);
    }
}
