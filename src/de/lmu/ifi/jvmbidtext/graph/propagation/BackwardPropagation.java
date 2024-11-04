package de.lmu.ifi.jvmbidtext.graph.propagation;

import com.ibm.wala.ipa.slicer.Statement;
import de.lmu.ifi.jvmbidtext.graph.model.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

class BackwardPropagation {
    private static final Logger logger = LogManager.getLogger(BackwardPropagation.class);
    private final TypingGraph typingGraph;
    private List<TypingRecord> worklist;
    private TypingRecord record;
    private List<TypingConstraint> phiList;
    private List<TypingConstraint> geAssignList;

    BackwardPropagation(TypingGraph typingGraph) {
        this.typingGraph = typingGraph;
    }

    boolean propagateOneRecordBackward(List<TypingRecord> worklist, TypingRecord typingRecord) {
        this.worklist = worklist;
        this.record = typingRecord;
        boolean changed = setupLists();
        int galSize = geAssignList.size();

        switch (galSize) {
            case 0:
                break;
            case 1:
                changed |= handleGeAssignListSize1();
                break;
            case 2:
                changed |= handleGeAssignListSize2();
                break;
            default:
                logger.error("    ? Wrong assignment with {} RHS: {}", galSize, geAssignList.toString());
        }
        changed |= backwardPropagateForPhi();
        return changed;
    }

    private boolean handleGeAssignListSize1() {
        TypingConstraint c = geAssignList.removeFirst();
        TypingNode nextNode = typingGraph.getNode(c.getRhs());
        TypingRecord nextRec = typingGraph.getTypingRecord(c.getRhs());
        if (nextNode != null && !nextNode.isConstant() && nextRec.merge(record, c.getPath())) {
            worklist.add(nextRec);
            return true;
        }
        return false;
    }

    private boolean handleGeAssignListSize2() {
        TypingConstraint tc0 = geAssignList.removeFirst();
        TypingConstraint tc1 = geAssignList.removeFirst();
        if (tc0.getLhs() != tc1.getLhs()) {
            logger.error("    ? Wrong Assignment with different LHS: {} -- {}", tc0.toString(), tc1.toString());
            return false;
        }
        TypingRecord rec0 = typingGraph.getTypingRecord(tc0.getRhs());
        TypingRecord rec1 = typingGraph.getTypingRecord(tc1.getRhs());
        DoubleChangeTracker changeTracker = new DoubleChangeTracker();

        propagateTexts(rec0, rec1, tc0, changeTracker);
        propagateConstants(rec0, rec1, changeTracker);
        propagateInputs(rec0, rec1, tc0, changeTracker);
        propagateOutputs(rec0, rec1, tc0, changeTracker);

        // propagate
        boolean changed = false;
        if (changeTracker.isChanged0()) {
            worklist.add(rec0);
            changed = true;
        }
        if (changeTracker.isChanged1()) {
            worklist.add(rec1);
            changed = true;
        }
        return changed;

    }

    private void propagateConstants(TypingRecord rec0, TypingRecord rec1,
                                    DoubleChangeTracker changeTracker) {
        Set<Object> const0 = rec0.getTypingConstants();
        Set<Object> const1 = rec1.getTypingConstants();
        Set<Object> constr = record.getTypingConstants();
        if (!constr.isEmpty()) {
            if (const0.isEmpty() && const1.isEmpty()) {
                const0.addAll(constr);
                const1.addAll(constr);
                changeTracker.changeBoth();
            } else if (const0.isEmpty() || const1.isEmpty()) {
                Set<Object> empty, nonEmpty;
                boolean isZero;
                if (const0.isEmpty()) {
                    isZero = true;
                    empty = const0;
                    nonEmpty = const1;
                } else {
                    isZero = false;
                    empty = const1;
                    nonEmpty = const0; // was also const1 before! But that seems wrong.
                }
                empty.addAll(constr);
                empty.removeAll(nonEmpty);
                if (!empty.isEmpty()) {
                    changeTracker.change(isZero);
                }
            }
        }
    }

    private void propagateTexts(TypingRecord rec0, TypingRecord rec1, TypingConstraint tc0,
                                DoubleChangeTracker changeTracker) {
        Map<String, List<Statement>> texts0 = rec0.getTypingTexts();
        Map<String, List<Statement>> texts1 = rec1.getTypingTexts();
        Map<String, List<Statement>> texts = record.getTypingTexts();

        propagationHelperMaps(tc0, changeTracker, texts0, texts1, texts, true);
    }

    private void propagateInputs(TypingRecord rec0, TypingRecord rec1, TypingConstraint tc0,
                                 DoubleChangeTracker changeTracker) {
        Map<SimpleGraphNode, List<Statement>> input0 = rec0.getInputFields();
        Map<SimpleGraphNode, List<Statement>> input1 = rec1.getInputFields();
        Map<SimpleGraphNode, List<Statement>> inputs = record.getInputFields();
        propagationHelperMaps(tc0, changeTracker, input0, input1, inputs, false);
    }

    private void propagateOutputs(TypingRecord rec0, TypingRecord rec1,
                                  TypingConstraint tc0, DoubleChangeTracker changeTracker) {
        Map<SimpleGraphNode, List<Statement>> output0 = rec0.getOutputFields();
        Map<SimpleGraphNode, List<Statement>> output1 = rec1.getOutputFields();
        Map<SimpleGraphNode, List<Statement>> outputs = record.getOutputFields();
        propagationHelperMaps(tc0, changeTracker, output0, output1, outputs, false);
    }

    private <T> void propagationHelperMaps(TypingConstraint tc0, DoubleChangeTracker changeTracker,
                                           Map<T, List<Statement>> map0,
                                           Map<T, List<Statement>> map1,
                                           Map<T, List<Statement>> map,
                                           boolean modifyOnBothEmpty) {
        if (map.isEmpty()) {
            return;
        }
        Map<T, List<Statement>> usedForPathUpdate = new HashMap<>(0);
        if (map0.isEmpty() && map1.isEmpty()) {
            if (modifyOnBothEmpty) {
                map0.putAll(map);
                map1.putAll(map);
                changeTracker.changeBoth();
            }
            usedForPathUpdate = map;
        } else if (map0.isEmpty() || map1.isEmpty()) {
            Map<T, List<Statement>> empty, nonEmpty;
            boolean isZero;
            if (map0.isEmpty()) {
                isZero = true;
                empty = map0;
                nonEmpty = map1;
            } else {
                isZero = false;
                empty = map1;
                nonEmpty = map0;
            }
            empty.putAll(map);
            for (T t : nonEmpty.keySet()) {
                empty.remove(t);
            }
            if (empty.isEmpty()) {
                return;
            }
            changeTracker.change(isZero);
            usedForPathUpdate = empty;
        }

        for (Map.Entry<T, List<Statement>> entry : usedForPathUpdate.entrySet()) {
            List<Statement> path = entry.getValue();
            if (path != null) {
                path.addAll(tc0.getPath());
            }
        }
    }

    private boolean setupLists() {
        boolean changed = false;
        Set<TypingConstraint> constraints = record.getBackwardTypingConstraints();
        geAssignList = new LinkedList<>();
        phiList = new LinkedList<>();
        for (TypingConstraint ct : constraints) {
            int nextId = ct.getRhs();
            int sym = ct.getSym();
            TypingNode nextNode;
            TypingRecord nextRec = typingGraph.getTypingRecord(nextId);
            if (sym == TypingConstraint.EQ || sym == TypingConstraint.GE) {
                nextNode = typingGraph.getNode(nextId);
                if (nextNode != null && !nextNode.isConstant() && nextRec.merge(record, ct.getPath())) {
                    worklist.add(nextRec);
                    changed = true;
                }
            } else if (sym == TypingConstraint.GE_ASSIGN) {
                geAssignList.add(ct);
            } else if (sym == TypingConstraint.GE_APPEND) {
                nextNode = typingGraph.getNode(nextId);
                if (nextNode != null && !nextNode.isConstant() && nextRec.mergeIfEmptyTexts(record, ct.getPath())) {
                    worklist.add(nextRec);
                    changed = true;
                }
            } else if (sym == TypingConstraint.GE_PHI) {
                phiList.add(ct);
            }
            // else if (sym == TypingConstraint.GE_UNIDIR) {
            // System.err.println(ct.lhs + "  ->- " + nextId);
            // }
        }
        return changed;
    }

    /**
     * Propagation for PHI-type relationship.
     * <p>
     * x = phi x1, x2, ..., xn, and T(x) = T(x1) \/ T(x2) \/ ... \/ T(xn) \/
     * T(y). We propagate T(x) to xi as: T'(xi) = T(x) - \/T(xj)[x!=j], i.e.,
     * T'(xi) = T(xi) \/ T(y).
     */
    private boolean backwardPropagateForPhi() {
        if (phiList.isEmpty()) {
            return false;
        }

        // store all current states for all phi nodes
        Set<String> allTexts = new HashSet<>();
        Set<Object> allConst = new HashSet<>();
        Set<SimpleGraphNode> allInputs = new HashSet<>();
        Set<SimpleGraphNode> allOutputs = new HashSet<>();
        for (TypingConstraint typingConstraint : phiList) {
            TypingRecord tr = typingGraph.getTypingRecord(typingConstraint.getRhs());
            allTexts.addAll(tr.getTypingTexts().keySet());
            allConst.addAll(tr.getTypingConstants());
            allInputs.addAll(tr.getInputFields().keySet());
            allOutputs.addAll(tr.getOutputFields().keySet());
        }


        boolean anyChanged = false;
        for (TypingConstraint typingConstraint : phiList) {
            TypingNode tn = typingGraph.getNode(typingConstraint.getRhs());
            if (tn == null || tn.isConstant()) {
                continue;
            }
            TypingRecord typingRecord = typingGraph.getTypingRecord(typingConstraint.getRhs());

            boolean changed = false;
            changed |= phiPropagateTexts(typingConstraint, allTexts, typingRecord);
            changed |= phiPropagateConst(allConst, typingRecord);
            changed |= phiPropagateInputs(typingConstraint, allInputs, typingRecord);
            changed |= phiPropagateOutputs(typingConstraint, allOutputs, typingRecord);

            if (changed) {
                anyChanged = true;
                worklist.add(typingRecord);
            }
        }
        return anyChanged;
    }

    private boolean phiPropagateConst(Set<Object> allConst, TypingRecord typingRecord) {
        Set<Object> recConsts = record.getTypingConstants();
        Set<Object> tmpConst = new HashSet<>(recConsts);
        tmpConst.removeAll(allConst);
        Set<Object> targetConsts = typingRecord.getTypingConstants();
        targetConsts.addAll(tmpConst);
        return !tmpConst.isEmpty();
    }

    private boolean phiPropagateOutputs(TypingConstraint typingConstraint, Set<SimpleGraphNode> allOutputs,
                                        TypingRecord typingRecord) {
        return phiPropagationHelperMaps(typingConstraint, allOutputs, record.getOutputFields(),
                typingRecord.getOutputFields());
    }

    private boolean phiPropagateInputs(TypingConstraint typingConstraint, Set<SimpleGraphNode> allInputs,
                                       TypingRecord typingRecord) {
        return phiPropagationHelperMaps(typingConstraint, allInputs, record.getInputFields(),
                typingRecord.getInputFields());
    }

    private boolean phiPropagateTexts(TypingConstraint typingConstraint, Set<String> allTexts,
                                      TypingRecord typingRecord) {
        return phiPropagationHelperMaps(typingConstraint, allTexts, record.getTypingTexts(),
                typingRecord.getTypingTexts());
    }

    private <T> boolean phiPropagationHelperMaps(TypingConstraint typingConstraint, Set<T> allElems,
                                                 Map<T, List<Statement>> recElems,
                                                 Map<T, List<Statement>> targetElems) {
        Set<T> tmpElems = new HashSet<>(recElems.keySet());
        // remove all states coming from phi nodes; keep only public states
        tmpElems.removeAll(allElems);
        // propagate remainings to current node
        for (T elem : tmpElems) {
            List<Statement> existingPath = recElems.get(elem);
            List<Statement> path = null;
            if (existingPath != null) {
                path = new LinkedList<>(typingConstraint.getPath());
            }
            targetElems.put(elem, path);
        }
        return !tmpElems.isEmpty();
    }

    private static class DoubleChangeTracker {
        private boolean changed0;
        private boolean changed1;

        DoubleChangeTracker() {
            changed0 = false;
            changed1 = false;
        }

        boolean isChanged0() {
            return changed0;
        }

        boolean isChanged1() {
            return changed1;
        }

        void change0() {
            changed0 = true;
        }

        void change1() {
            changed1 = true;
        }

        void changeBoth() {
            changed0 = true;
            changed1 = true;
        }

        void change(boolean isZero) {
            if (isZero) {
                changed0 = true;
            } else {
                changed1 = true;
            }
        }
    }
}
