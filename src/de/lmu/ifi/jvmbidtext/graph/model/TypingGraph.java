package de.lmu.ifi.jvmbidtext.graph.model;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.graph.impl.DelegatingNumberedNodeManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class TypingGraph {
    private static Logger logger = LogManager.getLogger(TypingGraph.class);

    private Entrypoint entrypoint;
    public Map<CGNode, TypingSubGraph> subGraphs;
    private Set<Integer> possibleExternalInput;
    private Map<String, Set<TypingNode>> fieldIncoming; // possible incoming fields
    private Map<String, Set<TypingNode>> fieldOutgoing; // possible outgoing fields
    private final DelegatingNumberedNodeManager<TypingNode> nodeManager;
    public final Map<SimpleGraphNode, TypingRecord> node2Typing;

    public TypingGraph(Entrypoint e) {
        entrypoint = e;
        subGraphs = new HashMap<>();
        node2Typing = new HashMap<>();
        nodeManager = new DelegatingNumberedNodeManager<>();
    }

    public void updateFieldTypingRecords() {
        if (fieldIncoming != null) {
            Set<Map.Entry<String, Set<TypingNode>>> s = fieldIncoming.entrySet();
            for (Map.Entry<String, Set<TypingNode>> e : s) {
                Set<TypingNode> v = e.getValue();
                for (TypingNode t : v) {
                    TypingRecord rec = getTypingRecord(t.getGraphNodeId());
                    if (rec != null) {
                        rec.addInputField(t.getGraphNodeId());
                    }
                }
            }
        }
        if (fieldOutgoing != null) {
            Set<Map.Entry<String, Set<TypingNode>>> s = fieldOutgoing.entrySet();
            for (Map.Entry<String, Set<TypingNode>> e : s) {
                Set<TypingNode> v = e.getValue();
                for (TypingNode t : v) {
                    TypingRecord rec = getTypingRecord(t.getGraphNodeId());
                    if (rec != null) {
                        rec.addOutputField(t.getGraphNodeId());
                    }
                }
            }
        }
    }

    public TypingSubGraph findOrCreateSubGraph(CGNode node) {
        TypingSubGraph sg = subGraphs.get(node);
        if (sg == null) {
            sg = new TypingSubGraph(node, this);
            subGraphs.put(node, sg);
        }
        return sg;
    }

    public Iterator<CGNode> iterateAllCGNodes() {
        return subGraphs.keySet().iterator();
    }

    public void addNode(TypingNode node) {
        nodeManager.addNode(node);
        findOrCreateTypingRecord(node.getGraphNodeId()); // not originally in the code
    }

    public TypingNode getNode(int n) {
        return nodeManager.getNode(n);
    }

    public int getNumberOfNodes() {
        return nodeManager.getNumberOfNodes();
    }

    public Iterator<TypingNode> iterateNodes() {
        return nodeManager.iterator();
    }

    public Iterator<Map.Entry<SimpleGraphNode, TypingRecord>> iterateRecords() {
        return node2Typing.entrySet().iterator();
    }

    public TypingRecord findOrCreateTypingRecord(int nodeId) {
        SimpleGraphNode n = SimpleGraphNode.make(nodeId);
        TypingRecord r = node2Typing.get(n);
        if (r == null) {
            r = new TypingRecord(n);
            node2Typing.put(n, r);
        }
        return r;
    }

    public TypingRecord getTypingRecord(int nodeId) {
        SimpleGraphNode n = SimpleGraphNode.make(nodeId);
        return node2Typing.get(n);
    }

    public void setTypingRecord(int nodeId, TypingRecord rec) {
        SimpleGraphNode n = SimpleGraphNode.make(nodeId);
        node2Typing.put(n, rec);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TypingGraph g) {
            return entrypoint.equals(g.entrypoint);
        }
        return false;
    }

    /**
     * Record possible incoming fields based on existing possible external
     * input. It is called after the whole TypingGraph is built for an
     * entrypoint.
     */
    public void collectIncomingFields() {
        if (null != possibleExternalInput) {
            for (Integer iObj : possibleExternalInput) {
                TypingNode node = getNode(iObj);
                collectIncomingField(node);
            }
        }
    }

    private void collectIncomingField(TypingNode node) {
        if (null == fieldIncoming) {
            fieldIncoming = new HashMap<>();
        }
        FieldReference ref = node.getFieldRef();
        String sig = ref.getSignature();
        Set<TypingNode> nodeSet = fieldIncoming.computeIfAbsent(sig, k -> new HashSet<>());
        nodeSet.add(node);
    }

    public void collectOutgoingField(TypingNode node) {
        if (null == fieldOutgoing) {
            fieldOutgoing = new HashMap<>();
        }
        FieldReference ref = node.getFieldRef();
        String sig = ref.getSignature();
        Set<TypingNode> nodeSet = fieldOutgoing.computeIfAbsent(sig, k -> new HashSet<>());
        nodeSet.add(node);
    }

    public Iterator<TypingNode> iterateAllOutgoingFields(String sig) {
        Set<TypingNode> nodeSet;
        if (fieldOutgoing == null || (nodeSet = fieldOutgoing.get(sig)) == null) {
            return Collections.emptyIterator();
        }
        return nodeSet.iterator();
    }

    public Iterator<TypingNode> iterateAllIncomingFields(String sig) {
        Set<TypingNode> nodeSet;
        if (fieldIncoming == null || (nodeSet = fieldIncoming.get(sig)) == null) {
            return Collections.EMPTY_SET.iterator();
        }
        return nodeSet.iterator();
    }

    public void setPossibleExternalInput(int nodeId) {
        if (null == possibleExternalInput) {
            possibleExternalInput = new HashSet<>();
        }
        possibleExternalInput.add(nodeId);
    }

    public void unsetPossibleExternalInput(int nodeId) {
        if (null != possibleExternalInput) {
            possibleExternalInput.remove(nodeId);
        }
    }

    public boolean possibleExternalInput(TypingNode node) {
        if (!node.isField()) {
            return false;
        }
        return possibleExternalInput(node.getGraphNodeId());
    }

    public boolean possibleExternalInput(int nodeId) {
        if (null == possibleExternalInput) {
            return false;
        }
        return possibleExternalInput.contains(nodeId);
    }

    public boolean possibleExternalOutput(TypingNode node) {
        if (!node.isField()) {
            return false;
        }
        return fieldOutgoing.containsKey(node.getFieldRef().getSignature());
    }

    /**
     * *Do NOT use this method*. The implementation has problems - which nodes
     * can be removed. In addition, after simplifying, some records still has
     * constraints to the removed records that do not exist, causing
     * NullPointerException somewhere.
     */
    public void simplify() {
        int oSize = nodeManager.getNumberOfNodes();
        List<TypingNode> worklist = new LinkedList<>();
        Iterator<TypingNode> iter = iterateNodes();
        while (iter.hasNext()) {
            TypingNode tn = iter.next();
            if (tn.isConstant() || tn.isField() || tn.isSpecialNode()) {
                continue;
            }
            int nodeId = tn.getGraphNodeId();
            TypingRecord rec = getTypingRecord(nodeId);
            if (rec == null
                    || (!rec.hasConstants() && !rec.hasForwardConstraints())) {
                worklist.add(tn);
            }
        }
        while (!worklist.isEmpty()) {
            TypingNode tn = worklist.removeFirst();
            TypingRecord rec = getTypingRecord(tn.getGraphNodeId());
            if (rec != null) {
                Set<TypingConstraint> cons = rec.getBackwardTypingConstraints();
                Set<TypingConstraint> toRemove = new HashSet<>();
                for (TypingConstraint rc : cons) {
                    TypingRecord nrec = getTypingRecord(rc.getRhs());
                    if (nrec != null) {
                        Set<TypingConstraint> fwd = nrec.getForwardTypingConstraints();
                        for (TypingConstraint c : fwd) {
                            if (c.getLhs() == tn.getGraphNodeId()) {
                                toRemove.add(c);
                            }
                        }
                        fwd.removeAll(toRemove);

                        TypingNode ntn = getNode(rc.getRhs());
                        if (ntn != null) {
                            if (!ntn.isConstant() && !ntn.isField()
                                    && !ntn.isSpecialNode()
                                    && !nrec.hasForwardConstraints()
                                    && !nrec.hasConstants()) {
                                worklist.add(ntn);
                            }
                        }
                    }
                    toRemove.clear();
                }
                removeTypingRecord(tn.getGraphNodeId());
            }
            nodeManager.removeNode(tn);
        }
        logger.info("     - Simplifying Typing Graph: {} -> {}", oSize,
                nodeManager.getNumberOfNodes());
    }

    private void removeTypingRecord(int nodeId) {
        SimpleGraphNode sgn = SimpleGraphNode.make(nodeId);
        node2Typing.remove(sgn);
    }

    /**
     * @return TRUE - some nodes are maintained; FALSE - no nodes.
     */
    public boolean clearAtEnd() {
        Iterator<TypingNode> iter = iterateNodes();
        while (iter.hasNext()) {
            TypingNode tn = iter.next();
            TypingRecord rec = getTypingRecord(tn.getGraphNodeId());
            if ((tn.isField() || tn.isSpecialNode()) && (rec != null)) {
                rec.endOfLife(false);
                continue;
            }
            if (rec != null) {
                rec.endOfLife(true);
            }
            removeTypingRecord(tn.getGraphNodeId());
            nodeManager.removeNode(tn);
        }
        possibleExternalInput = null;
        subGraphs.clear(); // no use later
        subGraphs = null;
        // System.gc();
        return nodeManager.getNumberOfNodes() > 0;
    }

    public Entrypoint getEntrypoint() {
        return entrypoint;
    }
}
