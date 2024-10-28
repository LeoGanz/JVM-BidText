package edu.purdue.cs.toydroid.bidtext.graph.propagation;

import edu.purdue.cs.toydroid.bidtext.graph.TypingConstraint;
import edu.purdue.cs.toydroid.bidtext.graph.TypingGraph;
import edu.purdue.cs.toydroid.bidtext.graph.TypingNode;
import edu.purdue.cs.toydroid.bidtext.graph.TypingRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Set;

public class ForwardPropagation {
    private static final Logger logger = LogManager.getLogger(ForwardPropagation.class);
    private final TypingGraph typingGraph;

    ForwardPropagation(TypingGraph typingGraph) {
        this.typingGraph = typingGraph;
    }

    public boolean propagateOneRecordForward(List<TypingRecord> worklist, TypingRecord record) {
        boolean changed = false;
        Set<TypingConstraint> constraints = record.getForwardTypingConstraints();
        for (TypingConstraint ct : constraints) {
            int nextId = ct.getLhs();
            TypingNode nextNode = typingGraph.getNode(nextId);
            TypingRecord nextRec = typingGraph.getTypingRecord(nextId);
            // TODO: skipping if nextNode.isConstant() can be a problem!
            // e.g. for put operations in Map<String, String> where the value is a constant
            // if value is leaked before put, no leak will be detected because we skipped the propagation
            if (nextNode != null && !nextNode.isConstant() && nextRec.merge(record, ct.getPath())) {
                worklist.add(nextRec);
                changed = true;
                if (ct.getSym() == TypingConstraint.EQ) {
                    TypingNode currNode = typingGraph.getNode(ct.getRhs());
                    if (currNode != null && currNode.isConstant()) {
                        nextNode.markFakeConstantKind();
                    }
                }
            }
        }
        return changed;
    }
}
