package edu.purdue.cs.toydroid.bidtext.analysis;

import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import edu.purdue.cs.toydroid.bidtext.graph.SimpleGraphNode;
import edu.purdue.cs.toydroid.bidtext.graph.TypingGraph;
import edu.purdue.cs.toydroid.bidtext.graph.TypingNode;
import edu.purdue.cs.toydroid.bidtext.graph.TypingRecord;
import edu.purdue.cs.toydroid.bidtext.graph.construction.TypingGraphUtil;

import java.util.*;
import java.util.function.Predicate;

public class TextForFieldsCollector {
    private final TypingGraph graph;
    private final TypingRecord initialRecord;

    private final Map<String, List<Statement>> texts; // is result (modified by reference)!
    private final Set<Integer> constants; // is result (modified by reference)!

    private boolean isBackward;
    private Stack<TypingGraph> visitedTypingGraphs;
    private List<WorklistEntry> worklist;

    public TextForFieldsCollector(TypingGraph typingGraph, TypingRecord initialRecord,
                                  Map<String, List<Statement>> texts, Set<Integer> constants) {
        this.graph = typingGraph;
        this.initialRecord = initialRecord;
        this.texts = texts;
        this.constants = constants;
    }

    public void collect(boolean isBackward) {
        this.isBackward = isBackward;
        visitedTypingGraphs = new Stack<>();
        worklist = new LinkedList<>();
        // for now: modifies texts and constants by reference
        // TODO: refactor to return a new collections
        collectTextsForFieldsHelper(initialRecord, 0, new LinkedList<>());
    }

    private void collectTextsForFieldsHelper(TypingRecord record, int permLevel, List<Statement> fieldPath) {
        if (permLevel >= 2) {
            return;
        }
        int previousWorklistSize = worklist.size();
        Map<SimpleGraphNode, List<Statement>> sources;
        if (isBackward) {
            sources = record.getInputFields();
        } else {
            sources = record.getOutputFields();
        }
        visitedTypingGraphs.push(graph);
        for (Map.Entry<SimpleGraphNode, List<Statement>> sgnPath : sources.entrySet()) {
            TypingNode typingNode = graph.getNode(sgnPath.getKey().nodeId());
            if (!typingNode.isField()) {
                continue;
            }
            List<Statement> connectedPath = buildConnectedPath(fieldPath, sgnPath.getValue(),
                    pathElement -> startAddingPathElements(pathElement, typingNode), true);
            for (Map.Entry<Entrypoint, TypingGraph> entry : TypingGraphUtil.entry2Graph.entrySet()) {
                // Entrypoint ep = entry.getKey();
                TypingGraph typingGraph = entry.getValue();
                if (visitedTypingGraphs.contains(typingGraph)) {
                    continue;
                }
                Set<TypingRecord> targets = buildTargets(typingNode);
                if (!targets.isEmpty()) {
                    // connectedPath -> record field path
                    worklist.add(new WorklistEntry(permLevel + 1, targets, connectedPath));
                }
            }
        }
        dumpTextForFieldsViaWorklist(previousWorklistSize);
        visitedTypingGraphs.pop();
    }

    private Set<TypingRecord> buildTargets(TypingNode typingNode) {
        String sig = typingNode.getFieldRef().getSignature();// System.err.println(sig);
        Set<TypingRecord> targets = new HashSet<>();
        Iterator<TypingNode> fieldIterator;
        if (isBackward) {
            fieldIterator = graph.iterateAllOutgoingFields(sig);
        } else {
            fieldIterator = graph.iterateAllIncomingFields(sig);
        }
        while (fieldIterator.hasNext()) {
            TypingNode field = fieldIterator.next();
            TypingRecord fieldRecord = graph.getTypingRecord(field.getGraphNodeId());
            if (fieldRecord != null) {
                targets.add(fieldRecord);
            }
        }
        return targets;
    }

    private void dumpTextForFieldsViaWorklist(int initSize) {
        while (worklist.size() > initSize) {
            WorklistEntry worklistEntry = worklist.remove(initSize);
            int permLevel = worklistEntry.permLevel();
            Set<TypingRecord> recSet = worklistEntry.recordSet();
            List<Statement> fieldPath = worklistEntry.fieldPath();
            if (fieldPath.isEmpty()) {
                continue;
            }
            for (TypingRecord rec : recSet) {
                Map<String, List<Statement>> recTexts = rec.getTypingTexts();
                for (Map.Entry<String, List<Statement>> entry : recTexts.entrySet()) {
                    String key = entry.getKey();
                    List<Statement> path = entry.getValue();
                    if (path == null) {
                        texts.put(key, null); // insensitive text
                        continue;
                    }
                    List<Statement> connectedPath = buildConnectedPath(fieldPath, path, __ -> true, false);
                    if (!connectedPath.isEmpty()) {
                        texts.put(key, connectedPath); // sensitive text
                    }
                }
                Set<Object> consts = rec.getTypingConstants();
                for (Object c : consts) {
                    if (c instanceof Integer i) {
                        constants.add(i);
                    }
                }
                collectTextsForFieldsHelper(rec, permLevel + 1, fieldPath);
            }
        }
    }

    /**
     * Build a connected path consisting of otherPath followed by fieldPath if a connecting put/get statement pair is found.
     *
     * @param fieldPath                       if empty, result depends on returnOtherPathIfFieldPathEmpty.
     *                                        If not empty, the first element is the connecting statement.
     * @param otherPath                       if empty, return empty always (no connection found, from where to start connecting fieldPath)
     * @param returnOtherPathIfFieldPathEmpty if true, return otherPath if fieldPath empty, otherwise empty
     * @return empty if no connection and not returnOtherPathIfFieldPathEmpty, otherwise the connected path
     */
    private List<Statement> buildConnectedPath(List<Statement> fieldPath, List<Statement> otherPath,
                                               Predicate<Statement> predicateToStartAddingPathElements,
                                               boolean returnOtherPathIfFieldPathEmpty) {
        if (otherPath == null || otherPath.isEmpty()) {
            return new LinkedList<>();
        }
        if (fieldPath.isEmpty()) {
            return returnOtherPathIfFieldPathEmpty ? otherPath : new LinkedList<>();
        }
        List<Statement> connectedPath = new LinkedList<>();
        Statement connector = fieldPath.getFirst();
        boolean addElements = false;
        for (Statement pathElement : otherPath) {
            addElements |= predicateToStartAddingPathElements.test(pathElement);
            if (addElements) {
                connectedPath.add(pathElement);
                if (isConnectingStatement(pathElement, connector)) {
                    connectedPath.addAll(fieldPath);
                    return connectedPath;
                }
            }
        }
        return new LinkedList<>();
    }

    private boolean startAddingPathElements(Statement pathElement, TypingNode typingNode) {
        // TODO refactor to use a more general approach between this method, isConnectingStatement and getConnectorSignature
        if (pathElement instanceof NormalStatement nstmt) {
            SSAInstruction inst = nstmt.getInstruction();
            if (isBackward && inst instanceof SSAGetInstruction getInstruction) {
                return typingNode.getFieldRef().getSignature().equals(getInstruction.getDeclaredField().getSignature());
            } else if (!isBackward && inst instanceof SSAPutInstruction putInstruction) {
                return typingNode.getFieldRef().getSignature().equals(putInstruction.getDeclaredField().getSignature());
            }
        }
        return false;
    }

    /**
     * Find the connecting get / put statement pair in the field path.
     */
    private boolean isConnectingStatement(Statement pathElement, Statement connector) {
        if (!(pathElement instanceof NormalStatement nstmt)) {
            return false;
        }
        String connectorSig = getConnectorSignature(connector);
        if (connectorSig == null) {
            // statement is not a connector
            return false;
        }
        SSAInstruction inst = nstmt.getInstruction();
        // notice the inverted logic for isBackward in comparison to getConnectorSignature
        if (!isBackward && inst instanceof SSAGetInstruction getInstruction) {
            return connectorSig.equals(getInstruction.getDeclaredField().getSignature());
        } else if (isBackward && inst instanceof SSAPutInstruction putInstruction) {
            return connectorSig.equals(putInstruction.getDeclaredField().getSignature());
        }
        return false;
    }

    private String getConnectorSignature(Statement connector) {
        if (connector instanceof NormalStatement nstmt) {
            SSAInstruction inst = nstmt.getInstruction();
            if (isBackward && inst instanceof SSAGetInstruction getInstruction) {
                return getInstruction.getDeclaredField().getSignature();
            } else if (!isBackward && inst instanceof SSAPutInstruction putInstruction) {
                return putInstruction.getDeclaredField().getSignature();
            }
        }
        return null;
    }

    private record WorklistEntry(int permLevel, Set<TypingRecord> recordSet, List<Statement> fieldPath) {

    }
}
