package edu.purdue.cs.toydroid.bidtext.graph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.FieldReference;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TypingSubGraph {

    public CGNode cgNode;
    public TypingGraph typingGraph;
    private int fakeInitialValueNumber;
    private int fakeValue;
    private Set<TypingNode> stringConstantNodes;
    private Map<Integer, TypingNode> value2Nodes;
    private Map<FieldReference, TypingNode> staticField2Nodes;
    private IR ir;
    private SymbolTable symTable;
    private Set<SSAAbstractInvokeInstruction> potentialGlobalConstStringLoc;

    public TypingSubGraph(CGNode node, TypingGraph c) {
        cgNode = node;
        typingGraph = c;
        stringConstantNodes = new HashSet<>();
        value2Nodes = new HashMap<>();
        staticField2Nodes = new HashMap<>();
        ir = cgNode.getIR();
        if (ir != null) {
            symTable = ir.getSymbolTable();
            fakeInitialValueNumber = ir.getControlFlowGraph()
                    .getNumberOfNodes() * 1000 + 999;
        } else {
            symTable = new SymbolTable(cgNode.getMethod()
                    .getNumberOfParameters());
            fakeInitialValueNumber = 999;
        }
        fakeValue = fakeInitialValueNumber;
    }

    public TypingNode getByValue(Integer v) {
        return value2Nodes.get(v);
    }

    public void setValueNode(Integer v, TypingNode node) {
        value2Nodes.put(v, node);
    }

    public TypingNode find(int v) {
        return value2Nodes.get(v);
    }

    public TypingNode findOrCreate(int v) {
        TypingNode node = value2Nodes.get(v);
        if (node == null) {
            node = new TypingNode(cgNode, v);
            value2Nodes.put(v, node);
            typingGraph.addNode(node);
            if (symTable.isStringConstant(v)) {
                node.markStringKind();
                stringConstantNodes.add(node);
            } else if (symTable.isConstant(v)) {
                node.markConstantKind();
            }
        }
        return node;
    }

    public TypingNode createInstanceFieldNode(int v, FieldReference f) {
        int fv = nextFakeValue();
        TypingNode node = new TypingNode(cgNode, fv, v, f);
        value2Nodes.put(fv, node);
        typingGraph.addNode(node);
        return node;
    }

    public TypingNode createStaticFieldNode(FieldReference f) {
        int fv = nextFakeValue();
        TypingNode node = new TypingNode(cgNode, fv, f);
        value2Nodes.put(fv, node);
        typingGraph.addNode(node);
        return node;
    }

    public TypingNode createFakeConstantNode() {
        int fv = nextFakeValue();
        TypingNode node = new TypingNode(cgNode, fv);
        node.markFakeStringKind();
        value2Nodes.put(fv, node);
        typingGraph.addNode(node);
        return node;
    }

    public int nextFakeValue() {
        int fv = fakeValue++;
        return fv;
    }

    public void recordPotentialGStringLocation(SSAAbstractInvokeInstruction inst) {
        if (potentialGlobalConstStringLoc == null) {
            potentialGlobalConstStringLoc = new HashSet<>();
        }
        potentialGlobalConstStringLoc.add(inst);
    }

    public Set<SSAAbstractInvokeInstruction> potentialGStringLocations() {
        return potentialGlobalConstStringLoc;
    }
}
