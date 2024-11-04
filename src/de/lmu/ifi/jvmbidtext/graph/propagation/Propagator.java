package de.lmu.ifi.jvmbidtext.graph.propagation;

import de.lmu.ifi.jvmbidtext.graph.model.SimpleGraphNode;
import de.lmu.ifi.jvmbidtext.graph.model.TypingGraph;
import de.lmu.ifi.jvmbidtext.graph.model.TypingNode;
import de.lmu.ifi.jvmbidtext.graph.model.TypingRecord;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Propagator {

    private static final Pattern NAME_VALUE_PATTERN = Pattern.compile("((\\w|-|\\s)+[=:]\\+\\*\\^\\d+\\^\\*\\+)");
    private final TypingGraph typingGraph;
    private final BackwardPropagation backwardPropagation;
    private final ForwardPropagation forwardPropagation;
    private List<TypingRecord> worklist;

    public Propagator(TypingGraph graph) {
        this.typingGraph = graph;
        this.backwardPropagation = new BackwardPropagation(graph);
        this.forwardPropagation = new ForwardPropagation(graph);
    }

    public void propagate() {
        worklist = new LinkedList<>();
        // PASS 1: forward
        initWorklistPassOne();
        // currentTypingGraph.simplify();
        while (!worklist.isEmpty()) {
            TypingRecord rec = worklist.removeFirst();
            forwardPropagation.propagateOneRecordForward(worklist, rec);
        }
        // PASS 2: forward & backward
        initWorklistPassTwo();
        while (!worklist.isEmpty()) {
            TypingRecord rec = worklist.removeFirst();
            backwardPropagation.propagateOneRecordBackward(worklist, rec);
            forwardPropagation.propagateOneRecordForward(worklist, rec);
            TypingNode node = typingGraph.getNode(rec.getInitialId());
            if (node != null && !(node.isField() || node.isSpecialNode())) {
                rec.emptyThePaths();
            }
        }
        // // old implementation which cannot leverage emptyThePaths to improve
        // // memory efficiency
        // while (true) {
        // boolean changed = false;
        // // PASS 2: backward
        // initWorklistPassTwo(worklist);
        // while (!worklist.isEmpty()) {
        // TypingRecord rec = worklist.remove(0);
        // changed = changed | propagateOneRecordBackward(rec, worklist);
        // }
        // if (!changed) {
        // break;
        // }
        // // PASS 3: forward
        // initWorklistPassThree(worklist);
        // while (!worklist.isEmpty()) {
        // TypingRecord rec = worklist.remove(0);
        // changed = changed | propagateOneRecordForward(rec, worklist);
        // }
        // if (!changed) {
        // break;
        // }
        // }
    }

    private void initWorklistPassOne() {
        Iterator<TypingNode> iter = typingGraph.iterateNodes();
        while (iter.hasNext()) {
            TypingNode tn = iter.next();
            TypingRecord rec = typingGraph.getTypingRecord(tn.getGraphNodeId());

            if (rec == null) {
                continue;
            }
            if (tn.isConstant()) {
                // if (rec == null) {
                // rec =
                // currentTypingGraph.findOrCreateTypingRecord(tn.getGraphNodeId());
                // }
                if (tn.isString()) {
                    String text = tn.getCgNode().getIR().getSymbolTable().getStringValue(tn.getValue());
                    if (text != null) {
                        text = text.trim();
                        if (!text.isEmpty()) {
                            rec.addTypingText(text);
                            worklist.add(rec);
                        }
                    }
                } else if (tn.isFakeString()) {
                    worklist.add(rec);
                } else {
                    Object o = tn.getCgNode().getIR().getSymbolTable().getConstantValue(tn.getValue());
                    rec.addTypingConstant(o);
                    worklist.add(rec);
                }
            } else if (/* rec != null && */rec.hasExternalFields()) {
                worklist.add(rec);
            }
            Iterator<String> appendIter = rec.iteratorAppendResults();
            while (appendIter.hasNext()) {
                String str = appendIter.next();
                // System.err.println(tn.getGraphNodeId() + "  >> "+str);
                if (str.startsWith("http:") || str.startsWith("https:")) {
                    str = str.substring(5);
                }
                Matcher matcher = NAME_VALUE_PATTERN.matcher(str);
                while (matcher.find()) {
                    // System.err.println(matcher.groupCount() + "  "
                    // + matcher.group(1));
                    String matched = matcher.group(1);
                    int vStartIdx = matched.indexOf(TypingRecord.APPEND_VAR_PREFIX);
                    String varStr = matched.substring(vStartIdx + TypingRecord.APPEND_VAR_PREFIX.length(),
                            matched.length() - TypingRecord.APPEND_VAR_POSTFIX.length());
                    int seperatorIdx = matched.indexOf('=');
                    if (seperatorIdx <= 0) {
                        seperatorIdx = matched.indexOf(':');
                    }
                    String name = matched.substring(0, seperatorIdx);
                    int var = Integer.parseInt(varStr);
                    TypingRecord r = typingGraph.getTypingRecord(var);
                    r.addTypingText(name);
                }
            }
        }
    }

    private void initWorklistPassTwo() {
        Iterator<Map.Entry<SimpleGraphNode, TypingRecord>> iter = typingGraph.iterateRecords();
        while (iter.hasNext()) {
            Map.Entry<SimpleGraphNode, TypingRecord> entry = iter.next();
            TypingRecord record = entry.getValue();
            if (record.hasBackwardConstraints() && (record.hasConstants() || record.hasExternalFields())) {
                worklist.add(record);
            }
        }
    }


    private void initWorklistPassThree(List<TypingRecord> worklist) {
        Iterator<Map.Entry<SimpleGraphNode, TypingRecord>> iter = typingGraph.iterateRecords();
        while (iter.hasNext()) {
            Map.Entry<SimpleGraphNode, TypingRecord> entry = iter.next();
            TypingRecord record = entry.getValue();
            if (record.hasForwardConstraints() && (record.hasConstants() || record.hasExternalFields())) {
                worklist.add(record);
            }
        }
    }
}
