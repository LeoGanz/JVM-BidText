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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class InvocationHandler {

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
    private TypingRecord returnValueRecord;
    private TypingRecord thisRecord;

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
        for (int i = offsetInUses; i < instruction.getNumberOfPositionalParameters(); i++) {
            int use = instruction.getUse(i);
            TypingNode node = subGraph.findOrCreate(use);
            if (node.isConstant()) {
                constantNodes.add(node);
            } else {
                freeNodes.add(node);
            }
        }

        if (instruction.hasDef()) {
            returnValueNode = subGraph.findOrCreate(instruction.getDef());
            returnValueRecord = typingGraph.findOrCreateTypingRecord(returnValueNode.getGraphNodeId());
        }
        if (!instruction.isStatic()) {
            thisNode = subGraph.findOrCreate(instruction.getReceiver());
            thisRecord = typingGraph.findOrCreateTypingRecord(thisNode.getGraphNodeId());
            // possibly mark for skipping depending on the class name of inst.getDeclaredTarget().getDeclaringClass()
        }
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
        TypingRecord paramRec = typingGraph.findOrCreateTypingRecord(paramNode.getGraphNodeId());
        if (paramNode.isConstant()) {
            String str;
            if (paramNode.isString()) {
                str = cgNode.getIR().getSymbolTable().getStringValue(paramNode.value);
            } else {
                str = cgNode.getIR().getSymbolTable().getConstantValue(paramNode.value).toString();
            }
            thisRecord.addTypingAppend(str);
        } else {
            thisRecord.addTypingAppend(paramNode.getGraphNodeId());
        }
        buildConstraint(thisNode, thisRecord, paramNode, paramRec, TypingConstraint.GE_APPEND, true, false);
        if (returnValueRecord != null) {
            returnValueRecord.addTypingAppend(thisRecord);
            buildConstraint(returnValueNode, returnValueRecord, paramNode, paramRec, TypingConstraint.GE_APPEND, true);
            buildConstraint(returnValueNode, returnValueRecord, thisNode, thisRecord, TypingConstraint.GE_APPEND, true);
        }
    }

    private void handleStringBuilderToString() {
        if (returnValueRecord != null) {
            buildConstraint(returnValueNode, returnValueRecord, thisNode, thisRecord, TypingConstraint.EQ, true);
        }
    }


    private boolean matchesPropagationRule() {
        String sig = WalaUtil.getSignature(instruction);
        String apiRule = APIPropagationRules.getRule(sig);
        return apiRule != null;
    }

    private void handleAPIByRule() {
        // TODO: refactor
        String sig = WalaUtil.getSignature(instruction);
        String concatenatedRules = APIPropagationRules.getRule(sig);
        String[] rules = concatenatedRules.split(",");
        int[] ruleRep = new int[3];
        TypingNode leftNode = null, rightNode = null;
        TypingRecord leftRec = null, rightRec = null;
        for (String s : rules) {
            String R = s.trim();
            ruleRep[1] = APIPropagationRules.NOTHING;
            APIPropagationRules.parseRule(R, ruleRep);
            if (ruleRep[1] == APIPropagationRules.NOTHING) {
                continue;
            }
            int leftIdx = ruleRep[0];
            int rightIdx = ruleRep[2];
            int op = ruleRep[1];
            if (leftIdx == -1) {
                leftNode = returnValueNode;
                leftRec = returnValueRecord;
            } else if (leftIdx < instruction.getNumberOfUses()) {
                int use = instruction.getUse(leftIdx);
                leftNode = subGraph.find(use);
                if (leftNode != null) {
                    leftRec = typingGraph.findOrCreateTypingRecord(leftNode.getGraphNodeId());
                }
            }
            if (rightIdx == -1) {
                rightNode = returnValueNode;
                rightRec = returnValueRecord;
            } else if (rightIdx < instruction.getNumberOfUses()) {
                int use = instruction.getUse(rightIdx);
                rightNode = subGraph.find(use);
                if (rightNode != null) {
                    rightRec = typingGraph.findOrCreateTypingRecord(rightNode.getGraphNodeId());
                }
            }
            if (leftRec != null && rightRec != null) {
                if (op == APIPropagationRules.LEFT_PROP) {
                    buildConstraint(leftNode, leftRec, rightNode, rightRec, TypingConstraint.GE, false);
                } else if (op == APIPropagationRules.RIGHT_PROP) {
                    buildConstraint(rightNode, rightRec, leftNode, leftRec, TypingConstraint.GE, false);
                } else { // dual propagation
                    buildConstraint(leftNode, leftRec, rightNode, rightRec, TypingConstraint.GE, true);
                }
            }
        }
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
        buildConstraint(returnValueNode, returnValueRecord, fakeNode, fakeRec, TypingConstraint.GE_UNIDIR, false);
    }

    private void handleGenericInvocation() {
        int apiConstraint = determineTypingConstraint();
        for (TypingNode pNode : freeNodes) {
            TypingRecord pRec = typingGraph.findOrCreateTypingRecord(pNode.getGraphNodeId());
            for (TypingNode cNode : constantNodes) {
                TypingRecord cRec = typingGraph.findOrCreateTypingRecord(cNode.getGraphNodeId());
                buildConstraint(pNode, pRec, cNode, cRec, TypingConstraint.GE, false);
            }
            if (apiType != InterestingNodeType.SINK) {
                if (thisRecord != null /* && !skipThis*/) {
                    buildConstraint(thisNode, thisRecord, pNode, pRec, apiConstraint, true);
                } else if (returnValueRecord != null) {
                    buildConstraint(returnValueNode, returnValueRecord, pNode, pRec, apiConstraint, true);
                }
            }
        }
        if (freeNodes.isEmpty() && apiType != InterestingNodeType.SINK) {
            for (TypingNode cNode : constantNodes) {
                TypingRecord cRec = typingGraph.findOrCreateTypingRecord(cNode.getGraphNodeId());
                if (thisRecord != null /* && !skipThis*/) {
                    buildConstraint(thisNode, thisRecord, cNode, cRec, TypingConstraint.GE, false);
                } else if (returnValueRecord != null) {
                    // backward constraint was in commented out code
                    buildConstraint(returnValueNode, returnValueRecord, cNode, cRec, TypingConstraint.GE, false);
                }
            }
        }
        if (thisRecord != null /*&& !skipThis*/ && returnValueRecord != null && apiType != InterestingNodeType.SINK) {
            buildConstraint(returnValueNode, returnValueRecord, thisNode, thisRecord, apiConstraint, true);
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

    private void buildConstraint(TypingNode leftNode, TypingRecord leftRecord, TypingNode rightNode,
                                 TypingRecord rightRecord, int constraintType, boolean addBackwardConstraint) {
        buildConstraint(leftNode, leftRecord, rightNode, rightRecord, constraintType, addBackwardConstraint, true);
    }

    private void buildConstraint(TypingNode leftNode, TypingRecord leftRecord, TypingNode rightNode,
                                 TypingRecord rightRecord, int constraintType, boolean addBackwardConstraint,
                                 boolean setPath) {
        TypingConstraint constraint =
                new TypingConstraint(leftNode.getGraphNodeId(), constraintType, rightNode.getGraphNodeId());
        if (setPath) {
            constraint.addPath(statement);
        }
        rightRecord.addForwardTypingConstraint(constraint);
        if (addBackwardConstraint) {
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
