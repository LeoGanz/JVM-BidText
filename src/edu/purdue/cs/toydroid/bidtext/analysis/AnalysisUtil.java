package edu.purdue.cs.toydroid.bidtext.analysis;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.*;
import edu.purdue.cs.toydroid.bidtext.graph.*;
import edu.purdue.cs.toydroid.bidtext.graph.construction.TypingGraphUtil;
import edu.purdue.cs.toydroid.utils.AnalysisConfig;
import edu.purdue.cs.toydroid.utils.ResourceUtil;
import edu.purdue.cs.toydroid.utils.WalaUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.*;

public class AnalysisUtil {
    private static final Logger logger = LogManager.getLogger(AnalysisUtil.class);

    private static final Set<InterestingNode> sinks = new HashSet<>();

    private static final Map<String, Integer> activity2Layout = new HashMap<>();

    private static InterestingNode latestInterestingNode = null;

    public static boolean DUMP_VERBOSE = true;

    public static void associateLayout2Activity(
            SSAAbstractInvokeInstruction instr, CGNode cgNode) {
        String act = instr.getDeclaredTarget()
                .getDeclaringClass()
                .getName()
                .toString();
        String selector = instr.getDeclaredTarget().getSelector().toString();
        if (ResourceUtil.isActivity(act)
                && "setContentView(I)V".equals(selector)) {
            SymbolTable symTable = cgNode.getIR().getSymbolTable();
            // only int constant is handled now.
            if (symTable.isIntegerConstant(instr.getUse(1))) {
                int layoutId = symTable.getIntValue(instr.getUse(1));
                activity2Layout.put(act, layoutId);
            }
        }
    }

    public static InterestingNodeType tryRecordInterestingNode(
            SSAAbstractInvokeInstruction instr, TypingSubGraph sg) {
        String sig = WalaUtil.getSignature(instr);
        String interestingIndices = AnalysisConfig.getPotentialSink(sig);
        latestInterestingNode = null;
        if (interestingIndices != null) {
            InterestingNode node = InterestingNode.getInstance(instr, sg, interestingIndices);
            sinks.add(node);
            String sinkClassName = instr.getDeclaredTarget().getDeclaringClass().getName().toString();
            String sinkMethodName = instr.getDeclaredTarget().getName().toString();
            String sinkLocationClassName = sg.getCgNode().getMethod().getDeclaringClass().getName().toString();
            String sinkLocationMethodName = sg.getCgNode().getMethod().getName().toString();
            logger.info("SINK: {}->{}() in [{}.{}()]",
                    sinkClassName, sinkMethodName, sinkLocationClassName, sinkLocationMethodName);
            latestInterestingNode = node;
            return InterestingNodeType.SINK;
        }
        return InterestingNodeType.NOTHING;
    }

    public static InterestingNode getLatestInterestingNode() {
        return latestInterestingNode;
    }

    /**
     * Dump all associated texts for interesting sinks.
     */
    public static void dumpTextForSinks() throws IOException {
        logger.info("Dump text for all sinks.");
        if (sinks.isEmpty()) {
            logger.warn("No interesting sinks are found.");
            return;
        }
        int idx = 0;
        for (InterestingNode sink : sinks) {
            dumpTextForSink(sink, idx++);
        }
        logger.info("Dumped text for {} sinks.", idx);
    }

    private static void dumpTextForSink(InterestingNode sink, int idx) throws IOException {
        logger.info(" - dump text for sink: {}", sink.sinkSignature());
        File resultFile = new File(idx + "." + sink.tag + ".txt");

        PrintWriter writer;
        try {
            writer = new PrintWriter(resultFile);
        } catch (IOException e) {
            logger.error("Fail to create dump file for [{}] {}", idx, sink.sinkSignature());
            if (resultFile.exists()) {
                resultFile.delete();
            }
            return;
        }
        printHeader(sink, writer);
        long fileLengthAfterWritingHeader = resultFile.length();

        Map<String, List<Statement>> codeTexts = new HashMap<>();
        Set<Integer> constants = new HashSet<>();
        Iterator<TypingNode> args = sink.iterateInterestingArgs();
        while (args.hasNext()) {
            TypingNode gNode = args.next();
            if (gNode.isConstant()) {
                continue;
            }
            TypingGraph graph = sink.enclosingTypingGraph();
            collectTextsForNode(gNode, graph, codeTexts, constants);
            collectTextsForFields(gNode, graph, codeTexts, constants);
        }

        TextAnalysis textAnalysis = new TextAnalysis();
        String sensitiveTag = textAnalysis.analyze(codeTexts, false);


        if (DUMP_VERBOSE) {
            if (!sensitiveTag.isEmpty()) {
                logger.debug("   $[CODE] {}", sensitiveTag);
                writer.print(" ^[CODE]: ");
                writer.print(sensitiveTag);
                writer.println();
            }
        }

        if (DUMP_VERBOSE) {
            printCollectedTexts(writer, codeTexts, constants);
        }
        // dumpTextForPossibleGUI(sink, writer);

        // dump paths
        printPaths(sink, textAnalysis, writer);

        writer.flush();
        writer.close();

        if (resultFile.exists() && resultFile.length() <= fileLengthAfterWritingHeader + 10) {
            logger.debug("No information found for sink. Deleting log file.");
            resultFile.delete();
        }
    }

    private static void printCollectedTexts(PrintWriter writer, Map<String, List<Statement>> codeTexts,
                                            Set<Integer> constants) {
        writer.println("Collected the following texts:");
        for (String t : codeTexts.keySet()) {
            writer.print(" - ");
            writer.print(t);
            writer.println();
        }
        for (Integer iObj : constants) {
            writer.print(" # 0x");
            writer.print(Integer.toHexString(iObj));
            writer.println();
            String guiText = ResourceUtil.getLayoutText(iObj);
            if (guiText != null && !guiText.isEmpty()) {
                writer.print(guiText);
                writer.println();
            }
        }
    }

    private static void printPaths(InterestingNode sink, TextAnalysis textAnalysis, PrintWriter writer) {
        Map<String, List<Statement>> text2Path = textAnalysis.getText2Path();
        Set<Map.Entry<String, List<Statement>>> pathSet = text2Path.entrySet();
        for (Map.Entry<String, List<Statement>> entry : pathSet) {
            String text = entry.getKey();
            List<Statement> path = entry.getValue();
            if (path == null/* || path.isEmpty() */) {
                continue;
            }
            writer.println();
            writer.println();
            writer.print("********");
            writer.print(text.trim());
            writer.print("********");
            writer.println();
            for (Statement stmt : path) {
                writer.print(stmt.toString());
                writer.println();
            }
            writer.print("[[ ");
            writer.print(sink.instruction());
            writer.print(" ]]");
            writer.println();
            writer.flush();
        }
    }

    private static void printHeader(InterestingNode sink, PrintWriter writer) {
        writer.print("SINK [");
        writer.print(sink.sinkSignature());
        writer.print(']');
        writer.println();
        writer.print(" in [");
        writer.print(sink.enclosingTypingSubGraph().getCgNode().getMethod().getSignature());
        writer.print(']');
        writer.println();
        writer.flush();
    }

    public static void collectTextsForNode(TypingNode node,
                                           TypingGraph graph, Map<String, List<Statement>> texts,
                                           Set<Integer> constants) {
        TypingRecord record = graph.getTypingRecord(node.getGraphNodeId());
        if (record == null) {
            return;
        }
        texts.putAll(record.getTypingTexts());
        for (Object o : record.getTypingConstants()) {
            if (o instanceof Integer) {
                constants.add((Integer) o);
            }
        }
    }

    // Fields that across entrypoints
    private static void collectTextsForFields(TypingNode node,
                                              TypingGraph graph, Map<String, List<Statement>> texts,
                                              Set<Integer> constants) {
        TypingRecord record = graph.getTypingRecord(node.getGraphNodeId());
        if (record == null) {
            return;
        }
        List<Statement> fieldPath = new LinkedList<>();
        Stack<TypingGraph> visited = new Stack<>();
        List<Object> worklist = new LinkedList<>();
        collectTextsForFieldsHelper(graph, record, 0, visited, fieldPath, worklist,
                texts, constants, true);
        visited.clear();
        worklist.clear();
        collectTextsForFieldsHelper(graph, record, 0, visited, fieldPath, worklist,
                texts, constants, false);
    }

    private static void collectTextsForFieldsHelper(TypingGraph graph,
                                                    TypingRecord record, int permLevel, Stack<TypingGraph> visited,
                                                    List<Statement> fieldPath, List<Object> worklist,
                                                    Map<String, List<Statement>> texts, Set<Integer> constants,
                                                    boolean isBackward) {
        if (permLevel >= 2) {
            return;
        }
        int worklistSize = worklist.size();
        Map<SimpleGraphNode, List<Statement>> sources;
        if (isBackward) {
            sources = record.getInputFields();
        } else {
            sources = record.getOutputFields();
        }
        visited.push(graph);
        for (SimpleGraphNode sgn : sources.keySet()) {
            TypingNode tn = graph.getNode(sgn.nodeId());
            if (tn.isField()) {
                List<Statement> tempPath = new LinkedList<>();
                boolean startAdd = false, endAdd = false;
                Statement connector = null;
                String connectorSig = "";
                if (!fieldPath.isEmpty()) {
                    connector = fieldPath.getFirst();
                    if (connector.getKind() == Statement.Kind.NORMAL) {
                        NormalStatement nstmt = (NormalStatement) connector;
                        SSAInstruction inst = nstmt.getInstruction();
                        if (isBackward && inst instanceof SSAGetInstruction) {
                            connectorSig = ((SSAGetInstruction) inst).getDeclaredField()
                                    .getSignature();
                        } else if (!isBackward
                                && inst instanceof SSAPutInstruction) {
                            connectorSig = ((SSAPutInstruction) inst).getDeclaredField()
                                    .getSignature();
                        }
                    }
                }
                List<Statement> sgnPath = sources.get(sgn);
                if (sgnPath != null) {
                    for (Statement p : sgnPath) {
                        if (p.getKind() == Statement.Kind.NORMAL) {
                            NormalStatement nstmt = (NormalStatement) p;
                            SSAInstruction inst = nstmt.getInstruction();
                            if (isBackward
                                    && inst instanceof SSAGetInstruction
                                    && tn.getFieldRef().getSignature()
                                    .equals((((SSAGetInstruction) inst).getDeclaredField().getSignature()))) {
                                startAdd = true;
                            } else if (!isBackward
                                    && inst instanceof SSAPutInstruction
                                    && tn.getFieldRef().getSignature()
                                    .equals((((SSAPutInstruction) inst).getDeclaredField().getSignature()))) {
                                startAdd = true;
                            }
                        }
                        if (startAdd) {
                            tempPath.add(p);
                            if (!fieldPath.isEmpty()
                                    && p.getKind() == Statement.Kind.NORMAL) {
                                NormalStatement nstmt = (NormalStatement) p;
                                SSAInstruction inst = nstmt.getInstruction();
                                if (!isBackward
                                        && inst instanceof SSAGetInstruction
                                        && connectorSig.equals(
                                        (((SSAGetInstruction) inst).getDeclaredField().getSignature()))) {
                                    endAdd = true;
                                } else if (isBackward
                                        && inst instanceof SSAPutInstruction
                                        && connectorSig.equals(
                                        (((SSAPutInstruction) inst).getDeclaredField().getSignature()))) {
                                    endAdd = true;
                                }
                            }
                            if (endAdd) {
                                break;
                            }
                        }
                    }
                }
                if (endAdd) {
                    tempPath.addAll(fieldPath);
                } else if (!fieldPath.isEmpty()) {
                    tempPath.clear();
                }
                String sig = tn.getFieldRef().getSignature();// System.err.println(sig);
                Map<Entrypoint, TypingGraph> entry2Graph = TypingGraphUtil.entry2Graph;
                Set<Map.Entry<Entrypoint, TypingGraph>> entrySet = entry2Graph.entrySet();
                for (Map.Entry<Entrypoint, TypingGraph> entry : entrySet) {
                    // Entrypoint ep = entry.getKey();
                    TypingGraph g = entry.getValue();
                    if (visited.contains(g)) {
                        continue;
                    }
                    Set<TypingRecord> targets = new HashSet<>();
                    Iterator<TypingNode> iter;
                    if (isBackward) {
                        iter = g.iterateAllOutgoingFields(sig);
                    } else {
                        iter = g.iterateAllIncomingFields(sig);
                    }
                    while (iter.hasNext()) {
                        TypingNode d = iter.next();
                        TypingRecord r = g.getTypingRecord(d.getGraphNodeId());
                        if (r != null) {
                            targets.add(r);
                        }
                    }
                    if (!targets.isEmpty()) {
                        worklist.add(g);
                        worklist.add(permLevel + 1);
                        worklist.add(targets);
                        worklist.add(tempPath);// record field path
                    }
                }
            }
        }
        dumpTextForFieldsViaWorklist(visited, worklist, worklistSize, texts,
                constants, isBackward);
        visited.pop();
    }

    private static void dumpTextForFieldsViaWorklist(
            Stack<TypingGraph> visited, List<Object> worklist, int initSize,
            Map<String, List<Statement>> texts, Set<Integer> constants,
            boolean isBackward) {
        while (worklist.size() > initSize) {
            TypingGraph graph = (TypingGraph) worklist.remove(initSize);
            int permLevel = (Integer) worklist.remove(initSize);
            Set<TypingRecord> recSet = (Set<TypingRecord>) worklist.remove(initSize);
            List<Statement> fs = (List<Statement>) worklist.remove(initSize);
            if (fs.isEmpty()) {
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
                    Statement connector = fs.getFirst();
                    String connectorSig = "";
                    if (connector.getKind() == Statement.Kind.NORMAL) {
                        NormalStatement nstmt = (NormalStatement) connector;
                        SSAInstruction inst = nstmt.getInstruction();
                        if (isBackward && inst instanceof SSAGetInstruction) {
                            connectorSig = ((SSAGetInstruction) inst).getDeclaredField()
                                    .getSignature();
                        } else if (!isBackward
                                && inst instanceof SSAPutInstruction) {
                            connectorSig = ((SSAPutInstruction) inst).getDeclaredField()
                                    .getSignature();
                        }
                    }
                    boolean endAdd = false;
                    for (Statement p : path) {
                        tempPath.add(p);
                        if (p.getKind() == Statement.Kind.NORMAL) {
                            NormalStatement nstmt = (NormalStatement) p;
                            SSAInstruction inst = nstmt.getInstruction();
                            if (!isBackward
                                    && inst instanceof SSAGetInstruction
                                    && connectorSig.equals(
                                    (((SSAGetInstruction) inst).getDeclaredField().getSignature()))) {
                                endAdd = true;
                            } else if (isBackward
                                    && inst instanceof SSAPutInstruction
                                    && connectorSig.equals(
                                    (((SSAPutInstruction) inst).getDeclaredField().getSignature()))) {
                                endAdd = true;
                            }
                        }
                        if (endAdd) {
                            break;
                        }
                    }
                    if (endAdd) {
                        tempPath.addAll(fs);
                        texts.put(key, tempPath);
                    }
                }
                Set<Object> consts = rec.getTypingConstants();
                for (Object c : consts) {
                    if (c instanceof Integer) {
                        constants.add((Integer) c);
                    }
                }
                collectTextsForFieldsHelper(graph, rec, permLevel + 1, visited, fs,
                        worklist, texts, constants, isBackward);
            }
        }
    }

    // currently only for the GUI that triggers the sink operation
    private static void dumpTextForPossibleGUI(InterestingNode sink,
                                               Map<String, List<Statement>> texts) {
        String epClass = sink.enclosingTypingGraph().getEntrypoint().getMethod()
                .getDeclaringClass()
                .getName()
                .toString();
        Integer layoutId = activity2Layout.get(epClass);
        if (layoutId != null) {
            String text = ResourceUtil.getLayoutText(layoutId);
            if (text != null) {
                try {
                    BufferedReader reader = new BufferedReader(
                            new StringReader(text));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // writer.print(" + ");
                        // writer.print(line);
                        // writer.println();
                        texts.put(line, null);
                    }
                } catch (IOException ignored) {

                }
            }
        }
    }

    static class IdCountPair implements Comparable<IdCountPair> {
        Integer id;
        int count;

        IdCountPair(Integer id) {
            this.id = id;
            count = 1;
        }

        void increment() {
            count++;
        }

        @Override
        public int compareTo(IdCountPair that) {
            return this.count - that.count;
        }
    }
}
