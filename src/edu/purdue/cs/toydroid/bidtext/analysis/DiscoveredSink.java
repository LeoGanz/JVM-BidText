package edu.purdue.cs.toydroid.bidtext.analysis;

import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import edu.purdue.cs.toydroid.bidtext.graph.TypingGraph;
import edu.purdue.cs.toydroid.bidtext.graph.TypingNode;
import edu.purdue.cs.toydroid.bidtext.graph.TypingSubGraph;
import edu.purdue.cs.toydroid.utils.WalaUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DiscoveredSink {
    private final SSAAbstractInvokeInstruction instruction;
    private final TypingSubGraph subGraph;
    private final SinkDefinitions.SinkDefinition sinkDefinition;

    public DiscoveredSink(SSAAbstractInvokeInstruction instruction, TypingSubGraph subGraph) {
        this.instruction = instruction;
        this.subGraph = subGraph;
        if (!SinkDefinitions.matchesSinkDefinition(instruction)) {
            throw new IllegalArgumentException("Instruction does not match any sink definition.");
        }
        this.sinkDefinition = SinkDefinitions.getSinkDefinition(instruction);
    }

    public String sinkSignature() {
        return WalaUtil.getSignature(instruction);
    }

    public TypingSubGraph enclosingTypingSubGraph() {
        return subGraph;
    }

    public TypingGraph enclosingTypingGraph() {
        return subGraph.getTypingGraph();
    }

    public List<TypingNode> getInterestingParameters() {
        List<TypingNode> interestingParameters = new ArrayList<>();
        for (int parameterIndex : sinkDefinition.indicesOfInterestingParameters()) {
            int use = instruction.getUse(parameterIndex);
            TypingNode parameterNode = subGraph.getByValue(use);
            if (parameterNode != null) {
                interestingParameters.add(parameterNode);
            }
        }
        return interestingParameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DiscoveredSink that = (DiscoveredSink) o;

        if (!Objects.equals(instruction, that.instruction)) {
            return false;
        }
        if (subGraph == null || that.subGraph == null) {
            return subGraph == that.subGraph;
        }
        return Objects.equals(subGraph.getCgNode(), that.subGraph.getCgNode());
    }

    @Override
    public int hashCode() {
        int result = instruction != null ? instruction.hashCode() : 0;
        result = 31 * result + (subGraph != null && subGraph.getCgNode() != null ? subGraph.getCgNode().hashCode() : 0);
        return result;
    }

    public SSAAbstractInvokeInstruction instruction() {
        return instruction;
    }

    public String getTag() {
        return sinkDefinition.tag();
    }
}
