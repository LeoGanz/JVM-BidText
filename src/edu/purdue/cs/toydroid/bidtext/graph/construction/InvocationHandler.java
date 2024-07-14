package edu.purdue.cs.toydroid.bidtext.graph.construction;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.slicer.NormalReturnCaller;
import com.ibm.wala.ipa.slicer.ParamCaller;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.types.MethodReference;
import edu.purdue.cs.toydroid.bidtext.analysis.AnalysisUtil;
import edu.purdue.cs.toydroid.bidtext.analysis.InterestingNode;
import edu.purdue.cs.toydroid.bidtext.analysis.InterestingNodeType;
import edu.purdue.cs.toydroid.bidtext.graph.*;
import edu.purdue.cs.toydroid.utils.WalaUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class InvocationHandler {
    private static final Logger logger = LogManager.getLogger(InvocationHandler.class);

    private static final List<String> namesOfMethodsRequiringUnidirectionalPropagation = new ArrayList<>() {{
        add("getInputStream");
        add("getOutputStream");
    }};

    private final TypingGraph typingGraph;

    private final TypingSubGraph subGraph;
    private final CGNode cgNode;
    private final Statement statement;
    private final SSAAbstractInvokeInstruction instruction;
    private InterestingNodeType apiType;

    private List<TypingNode> freeNodes;
    private List<TypingNode> constantNodes;
    private TypingNode returnValueNode;
    private TypingNode thisNode;

    public InvocationHandler(TypingGraph typingGraph, TypingSubGraph subGraph, CGNode cgNode, Statement statement,
                             SSAAbstractInvokeInstruction instruction) {
        this.typingGraph = typingGraph;
        this.subGraph = subGraph;
        this.cgNode = cgNode;
        this.statement = statement;
        this.instruction = instruction;
    }

    public void handle() {
        preProcessInterestingNode();
        if (hasNeitherParametersNorReturnValue()) {
            return;
        }
        initialize();
        if (isStringBuilderInvocation()) {
            processStringBuilder();
        } else if (matchesPropagationRule()) {
            handleAPIByRule();
        } else if (matchesSourceCorrelationRule()) {
            handleAPISourceByRule();
        } else {
            handleGenericInvocation();
        }
        postProcessInterestingNode();
    }

    private boolean hasNeitherParametersNorReturnValue() {
        return instruction.getNumberOfPositionalParameters() == 0 && !instruction.hasDef();
    }

    private void initialize() {
        freeNodes = new ArrayList<>();
        constantNodes = new ArrayList<>();
        // non-static methods have an implicit "this" parameter as the first parameter
        int offsetInUses = instruction.isStatic() ? 0 : 1;
        StringBuilder debugInfo = new StringBuilder("          Instruction values - uses:");
        for (int i = offsetInUses; i < instruction.getNumberOfPositionalParameters(); i++) {
            int use = instruction.getUse(i);
            debugInfo.append(" ").append(use);
            TypingNode node = subGraph.findOrCreate(use);
            if (node.isConstant()) {
                constantNodes.add(node);
            } else {
                freeNodes.add(node);
            }
        }

        if (instruction.hasDef()) {
            debugInfo.append(", defines (returns): ").append(instruction.getDef());
            returnValueNode = subGraph.findOrCreate(instruction.getDef());
        }
        if (!instruction.isStatic()) {
            debugInfo.append(", this reference: ").append(instruction.getReceiver());
            thisNode = subGraph.findOrCreate(instruction.getReceiver());
            // possibly mark for skipping depending on the class name of inst.getDeclaredTarget().getDeclaringClass()
        }
        logger.debug(debugInfo.toString());
    }

    private boolean isStringBuilderInvocation() {
        String apiSig = instruction.getDeclaredTarget().getSignature();
        return apiSig.startsWith("java.lang.StringBuilder");
    }

    private void processStringBuilder() {
        String apiSig = instruction.getDeclaredTarget().getSignature();
        if (apiSig.startsWith("java.lang.StringBuilder.append(") ||
                apiSig.startsWith("java.lang.StringBuilder.<init>(Ljava/")) {
            if ((statement instanceof ParamCaller paramCaller && paramCaller.getValueNumber() == instruction.getUse(1))
                    || statement instanceof NormalReturnCaller) {
                handleStringBuilderAppend(subGraph.find(instruction.getUse(1)));
            }
        } else if (apiSig.startsWith("java.lang.StringBuilder.toString(")) {
            handleStringBuilderToString();
        }
    }

    private void handleStringBuilderAppend(TypingNode paramNode) {
        TypingRecord thisRecord = typingGraph.findOrCreateTypingRecord(thisNode.getGraphNodeId());
        TypingRecord returnValueRecord = typingGraph.findOrCreateTypingRecord(returnValueNode.getGraphNodeId());
        if (paramNode.isConstant()) {
            String str;
            if (paramNode.isString()) {
                str = cgNode.getIR().getSymbolTable().getStringValue(paramNode.getValue());
            } else {
                str = cgNode.getIR().getSymbolTable().getConstantValue(paramNode.getValue()).toString();
            }
            thisRecord.addTypingAppend(str);
        } else {
            thisRecord.addTypingAppend(paramNode.getGraphNodeId());
        }
        buildConstraint(thisNode, paramNode, TypingConstraint.GE_APPEND, true, false);
        if (returnValueRecord != null) {
            returnValueRecord.addTypingAppend(thisRecord);
            buildConstraint(returnValueNode, paramNode, TypingConstraint.GE_APPEND, true);
            buildConstraint(returnValueNode, thisNode, TypingConstraint.GE_APPEND, true);
        }
    }

    private void handleStringBuilderToString() {
        if (returnValueNode != null) {
            buildConstraint(returnValueNode, thisNode, TypingConstraint.EQ, true);
        }
    }


    private boolean matchesPropagationRule() {
        String sig = WalaUtil.getSignature(instruction);
        Set<APIPropagationRules.Rule> apiRules = APIPropagationRules.getRules(sig);
        return !apiRules.isEmpty();
    }

    private void handleAPIByRule() {
        String sig = WalaUtil.getSignature(instruction);
        Set<APIPropagationRules.Rule> rules = APIPropagationRules.getRules(sig);
        for (APIPropagationRules.Rule rule : rules) {
            TypingNode leftNode = getTypingNodeForRuleValue(rule.left());
            TypingNode rightNode = getTypingNodeForRuleValue(rule.right());

            switch (rule.operator()) {
                case LEFT_PROP -> buildConstraint(leftNode, rightNode, TypingConstraint.GE, false);
                case RIGHT_PROP -> buildConstraint(rightNode, leftNode, TypingConstraint.GE, false);
                case DUAL_PROP -> buildConstraint(leftNode, rightNode, TypingConstraint.GE, true);
                case NO_PROP -> {
                }
            }
        }
    }

    private TypingNode getTypingNodeForRuleValue(APIPropagationRules.Rule.ValueIndex ruleValueIndex) {
        if (ruleValueIndex.isReturnValue()) {
            return returnValueNode;
        }
        if (ruleValueIndex.index() >= instruction.getNumberOfUses()) {
            throw new IllegalArgumentException("Rule index out of bounds. RuleValue was " + ruleValueIndex.index() +
                    " but instruction has only " + instruction.getNumberOfUses() + " uses.");
        }
        int paramValue = instruction.getUse(ruleValueIndex.index());
        return subGraph.find(paramValue);
    }

    private boolean matchesSourceCorrelationRule() {
        String sig = WalaUtil.getSignature(instruction);
        String apiRule = APISourceCorrelationRules.getRule(sig);
        return apiRule != null;
    }

    private void handleAPISourceByRule() {
        String sig = WalaUtil.getSignature(instruction);
        String rule = APISourceCorrelationRules.getRule(sig);
        TypingNode fakeNode = subGraph.createFakeConstantNode();
        TypingRecord fakeRec = typingGraph.findOrCreateTypingRecord(fakeNode.getGraphNodeId());
        fakeRec.addTypingText(rule);
        buildConstraint(returnValueNode, fakeNode, TypingConstraint.GE_UNIDIR, false);
    }

    private void handleGenericInvocation() {
        int apiConstraint = determineTypingConstraint();
        for (TypingNode pNode : freeNodes) {
            for (TypingNode cNode : constantNodes) {
                buildConstraint(pNode, cNode, TypingConstraint.GE, false);
            }
            if (apiType != InterestingNodeType.SINK) {
                if (thisNode != null /* && !skipThis*/) {
                    buildConstraint(thisNode, pNode, apiConstraint, true);
                } else if (returnValueNode != null) { // TODO remove "else" ?
                    buildConstraint(returnValueNode, pNode, apiConstraint, true);
                }
            }
        }
        if (freeNodes.isEmpty() && apiType != InterestingNodeType.SINK) {
            for (TypingNode cNode : constantNodes) {
                if (thisNode != null /* && !skipThis*/) {
                    buildConstraint(thisNode, cNode, TypingConstraint.GE, false);
                } else if (returnValueNode != null) {
                    // backward constraint was in commented out code
                    buildConstraint(returnValueNode, cNode, TypingConstraint.GE, false);
                }
            }
        }
        if (thisNode != null /*&& !skipThis*/ && returnValueNode != null && apiType != InterestingNodeType.SINK) {
            buildConstraint(returnValueNode, thisNode, apiConstraint, true);
            // backward constraint had the following commented out condition
            // if (apiConstraint != TypingConstraint.GE_UNIDIR)
        }
    }

    private int determineTypingConstraint() {
        MethodReference mRef = instruction.getDeclaredTarget();
        String methodName = mRef.getName().toString();
        if (namesOfMethodsRequiringUnidirectionalPropagation.contains(methodName)) {
            return TypingConstraint.GE_UNIDIR;
        } else {
            return TypingConstraint.GE;
        }
    }

    private void buildConstraint(TypingNode leftNode, TypingNode rightNode,
                                 int constraintType, boolean addBackwardConstraint) {
        buildConstraint(leftNode, rightNode, constraintType, addBackwardConstraint, true);
    }

    private void buildConstraint(TypingNode leftNode, TypingNode rightNode,
                                 int constraintType, boolean addBackwardConstraint,
                                 boolean setPath) {
        if (leftNode == null || rightNode == null) {
            return;
        }

        TypingConstraint constraint =
                new TypingConstraint(leftNode.getGraphNodeId(), constraintType, rightNode.getGraphNodeId());
        if (setPath) {
            constraint.addPath(statement);
        }

        TypingRecord rightRecord = typingGraph.findOrCreateTypingRecord(rightNode.getGraphNodeId());
        rightRecord.addForwardTypingConstraint(constraint);
        if (addBackwardConstraint) {
            TypingRecord leftRecord = typingGraph.findOrCreateTypingRecord(leftNode.getGraphNodeId());
            leftRecord.addBackwardTypingConstraint(constraint);
        }
    }

    private void preProcessInterestingNode() {
        apiType = AnalysisUtil.tryRecordInterestingNode(instruction, subGraph);
    }

    private void postProcessInterestingNode() {
        InterestingNode sink = AnalysisUtil.getLatestInterestingNode();
        if (apiType == InterestingNodeType.SINK && sink != null) {
            Iterator<TypingNode> sinkArgs = sink.iterateInterestingArgs();
            while (sinkArgs.hasNext()) {
                TypingNode t = sinkArgs.next();
                t.markSpecial();
            }
        }
    }
}
