package edu.purdue.cs.toydroid.bidtext.graph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.FieldReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TypingSubGraph {

    private static final Logger logger = LogManager.getLogger(TypingSubGraph.class);
    private final CGNode cgNode;
    private final TypingGraph typingGraph;
    private int fakeValue;
    private final Set<TypingNode> stringConstantNodes;
    public final Map<Integer, TypingNode> value2Nodes;
    private final SymbolTable symTable;
    private Set<SSAAbstractInvokeInstruction> potentialGlobalConstStringLoc;

    public TypingSubGraph(CGNode node, TypingGraph c) {
        this.cgNode = node;
        this.typingGraph = c;
        stringConstantNodes = new HashSet<>();
        value2Nodes = new HashMap<>();
        // set initial fake value number
        IR ir = cgNode.getIR();
        if (ir != null) {
            symTable = ir.getSymbolTable();
            fakeValue = ir.getControlFlowGraph()
                    .getNumberOfNodes() * 1000 + 999;
        } else {
            symTable = new SymbolTable(getCgNode().getMethod()
                    .getNumberOfParameters());
            fakeValue = 999;
        }
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
            node = new TypingNode(getCgNode(), v);
            value2Nodes.put(v, node);
            getTypingGraph().addNode(node);
            if (symTable.isStringConstant(v)) {
                node.markStringKind();
                stringConstantNodes.add(node);
            } else if (symTable.isConstant(v)) {
                node.markConstantKind();
            }
            logger.debug("          Created regular typing node: {}", node);
        }
        return node;
    }

    public TypingNode createInstanceFieldNode(int v, FieldReference f) {
        int fv = nextFakeValue();
        TypingNode node = new TypingNode(getCgNode(), fv, v, f);
        value2Nodes.put(fv, node);
        getTypingGraph().addNode(node);
        logger.debug("          Created instance field typing node: {}", node);
        return node;
    }

    public TypingNode createStaticFieldNode(FieldReference f) {
        int fv = nextFakeValue();
        TypingNode node = new TypingNode(getCgNode(), fv, f);
        value2Nodes.put(fv, node);
        getTypingGraph().addNode(node);
        logger.debug("          Created static field typing node: {}", node);
        return node;
    }

    public TypingNode createFakeConstantNode() {
        int fv = nextFakeValue();
        TypingNode node = new TypingNode(getCgNode(), fv);
        node.markFakeStringKind();
        value2Nodes.put(fv, node);
        getTypingGraph().addNode(node);
        logger.debug("          Created fake constant typing node: {}", node);
        return node;
    }

    public int nextFakeValue() {
        return fakeValue++;
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

    public CGNode getCgNode() {
        return cgNode;
    }

    public TypingGraph getTypingGraph() {
        return typingGraph;
    }

}
