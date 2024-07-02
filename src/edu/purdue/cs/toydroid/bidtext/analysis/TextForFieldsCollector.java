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

public class TextForFieldsCollector {
    private final TypingGraph graph;
    private final boolean isBackward;

    private final Map<String, List<Statement>> texts; // is result (modified by reference)!
    private final Stack<TypingGraph> visited;
    private final List<Object> worklist;
    List<Statement> fieldPath = new LinkedList<>();

    public TextForFieldsCollector(TypingGraph typingGraph, boolean isBackward, Map<String, List<Statement>> texts) {
        this.graph = typingGraph;
        this.isBackward = isBackward;
        this.texts = texts;
        visited = new Stack<>();
        worklist = new LinkedList<>();
    }

    private void collectTextsForFieldsHelper(TypingRecord record, int permLevel, List<Statement> fieldPath,

                                             Set<Integer> constants) {
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
        visited.push(graph);
        for (Map.Entry<SimpleGraphNode, List<Statement>> sgnPath : sources.entrySet()) {
            TypingNode typingNode = graph.getNode(sgnPath.getKey().nodeId());
            if (!typingNode.isField()) {
                continue;
            }
            List<Statement> tempPath = buildTempPathForSgn(fieldPath, sgnPath.getValue(), typingNode);
            for (Map.Entry<Entrypoint, TypingGraph> entry : TypingGraphUtil.entry2Graph.entrySet()) {
                // Entrypoint ep = entry.getKey();
                TypingGraph typingGraph = entry.getValue();
                if (visited.contains(typingGraph)) {
                    continue;
                }
                Set<TypingRecord> targets = buildTargets(typingNode);
                if (!targets.isEmpty()) {
                    worklist.add(permLevel + 1);
                    worklist.add(targets);
                    worklist.add(tempPath);// record field path
                }
            }
        }
        dumpTextForFieldsViaWorklist(previousWorklistSize, constants);
        visited.pop();
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

    private List<Statement> buildTempPathForSgn(List<Statement> fieldPath,
                                                List<Statement> sgnPath,
                                                TypingNode typingNode) {
        List<Statement> tempPath = new LinkedList<>();
        String connectorSig = "";
        boolean fieldPathHasElements = !fieldPath.isEmpty();
        if (fieldPathHasElements) {
            Statement connector = fieldPath.getFirst();
            connectorSig = getConnectorSignature(connector);
        }
        boolean endAdding = false;
        if (sgnPath != null) {
            boolean addElements = false;
            for (Statement sgnPathElement : sgnPath) {
                addElements |= startAddingPathElements(sgnPathElement, typingNode);
                if (addElements) {
                    tempPath.add(sgnPathElement);
                    endAdding = endAddingPathElements(fieldPathHasElements, sgnPathElement, connectorSig);
                    if (endAdding) {
                        tempPath.addAll(fieldPath);
                        break;
                    }
                }
            }
        }
        if (fieldPathHasElements && !endAdding) {
            tempPath.clear();
        }
        return tempPath;
    }

    private boolean endAddingPathElements(boolean fieldPathHasElements,
                                          Statement sgnPathElement,
                                          String connectorSig) {
        if (fieldPathHasElements) {
            if (sgnPathElement instanceof NormalStatement nstmt) {
                SSAInstruction inst = nstmt.getInstruction();
                if (!isBackward && inst instanceof SSAGetInstruction getInstruction &&
                        connectorSig.equals(getInstruction.getDeclaredField().getSignature())) {
                    return true;
                } else if (isBackward && inst instanceof SSAPutInstruction putInstruction &&
                        connectorSig.equals(putInstruction.getDeclaredField().getSignature())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean startAddingPathElements(Statement pathElement, TypingNode typingNode) {
        if (pathElement instanceof NormalStatement nstmt) {
            SSAInstruction inst = nstmt.getInstruction();
            if (isBackward && inst instanceof SSAGetInstruction getInstruction && typingNode.getFieldRef()
                    .getSignature()
                    .equals(getInstruction.getDeclaredField().getSignature())) {
                return true;
            } else if (!isBackward && inst instanceof SSAPutInstruction putInstruction &&
                    typingNode.getFieldRef()
                            .getSignature()
                            .equals(putInstruction.getDeclaredField().getSignature())) {
                return true;
            }
        }
        return false;
    }

    private String getConnectorSignature(Statement connector) {
        String connectorSig = "";
        if (connector instanceof NormalStatement nstmt) {
            SSAInstruction inst = nstmt.getInstruction();
            if (isBackward && inst instanceof SSAGetInstruction getInstruction) {
                connectorSig = getInstruction.getDeclaredField().getSignature();
            } else if (!isBackward && inst instanceof SSAPutInstruction putInstruction) {
                connectorSig = putInstruction.getDeclaredField().getSignature();
            }
        }
        return connectorSig;
    }

    private void dumpTextForFieldsViaWorklist(int initSize,
                                              Set<Integer> constants) {
        while (worklist.size() > initSize) {
            int permLevel = (int) worklist.remove(initSize);
            Set<TypingRecord> recSet = (Set<TypingRecord>) worklist.remove(initSize);
            List<Statement> fieldPath = (List<Statement>) worklist.remove(initSize);
            if (fieldPath.isEmpty()) {
                continue;
            }
            for (TypingRecord rec : recSet) {
                Map<String, List<Statement>> recTexts = rec.getTypingTexts();
                Set<Map.Entry<String, List<Statement>>> set = recTexts.entrySet();
                for (Map.Entry<String, List<Statement>> entry : set) {
                    String key = entry.getKey();
                    List<Statement> path = entry.getValue();
                    if (path == null) {
                        texts.put(key, null);// insensitive text
                        continue;
                    }
                    List<Statement> tempPath = new LinkedList<>();
                    Statement connector = fieldPath.getFirst();
                    String connectorSig = getConnectorSignature(connector);
                    boolean endAdd = false;
                    for (Statement p : path) {
                        tempPath.add(p);
                        if (p instanceof NormalStatement nstmt) {
                            SSAInstruction inst = nstmt.getInstruction();
                            if (!isBackward && inst instanceof SSAGetInstruction getInstruction && connectorSig.equals(
                                    getInstruction.getDeclaredField().getSignature())) {
                                endAdd = true;
                            } else if (isBackward && inst instanceof SSAPutInstruction putInstruction &&
                                    connectorSig.equals(
                                            putInstruction.getDeclaredField().getSignature())) {
                                endAdd = true;
                            }
                        }
                        if (endAdd) {
                            break;
                        }
                    }
                    if (endAdd) {
                        tempPath.addAll(fieldPath);
                        texts.put(key, tempPath);
                    }
                }
                Set<Object> consts = rec.getTypingConstants();
                for (Object c : consts) {
                    if (c instanceof Integer) {
                        constants.add((Integer) c);
                    }
                }
                collectTextsForFieldsHelper(rec, permLevel + 1, fieldPath, constants);
            }
        }
    }
}
