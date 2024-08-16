package edu.purdue.cs.toydroid.bidtext.analysis;

import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import edu.purdue.cs.toydroid.bidtext.graph.TypingGraph;
import edu.purdue.cs.toydroid.bidtext.graph.TypingNode;
import edu.purdue.cs.toydroid.bidtext.graph.TypingRecord;
import edu.purdue.cs.toydroid.bidtext.graph.TypingSubGraph;
import edu.purdue.cs.toydroid.utils.ResourceUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class AnalysisUtil {
    private static final Logger logger = LogManager.getLogger(AnalysisUtil.class);

    private static final Set<DiscoveredSink> sinks = new HashSet<>();
    public static final String REPORT_FOLDER = "report";

    public static boolean DUMP_VERBOSE = true;

    public static void recordSink(DiscoveredSink sink) {
        if (sinks.contains(sink)) {
            return;
        }
        sinks.add(sink);
        SSAAbstractInvokeInstruction instruction = sink.instruction();
        TypingSubGraph subGraph = sink.enclosingTypingSubGraph();
        String sinkClassName = instruction.getDeclaredTarget().getDeclaringClass().getName().toString();
        String sinkMethodName = instruction.getDeclaredTarget().getName().toString();
        String sinkLocationClassName = subGraph.getCgNode().getMethod().getDeclaringClass().getName().toString();
        String sinkLocationMethodName = subGraph.getCgNode().getMethod().getName().toString();
        logger.info("        SINK: {}->{}() in [{}.{}()]", sinkClassName, sinkMethodName, sinkLocationClassName,
                sinkLocationMethodName);
    }

    /**
     * Dump all associated texts for interesting sinks.
     */
    public static void dumpTextForSinks() throws IOException {
        logger.info("Dump text for all sinks.");
        clearSinksFromReportFolder();
        if (sinks.isEmpty()) {
            logger.warn("No interesting sinks are found.");
            return;
        }
        int idx = 0;
        for (DiscoveredSink sink : sinks) {
            dumpTextForSink(sink, idx++);
        }
        logger.info("Dumped text for {} sinks.", idx);
    }

    private static void dumpTextForSink(DiscoveredSink sink, int idx) throws IOException {
        logger.info(" - dump text for sink: {}", sink.sinkSignature());
        File resultFile = new File(REPORT_FOLDER + "/" + idx + "." + sink.getTag() + ".txt");

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

        Map<String, List<Statement>> codeTexts = new HashMap<>();
        Set<Integer> constants = new HashSet<>();
        sink.getInterestingParameters().stream()
                .filter(gNode -> !gNode.isConstant())
                .forEach(gNode -> {
                    TypingGraph graph = sink.enclosingTypingGraph();
                    collectTextsForNode(gNode, graph, codeTexts, constants);
                    collectTextsForFields(gNode, graph, codeTexts, constants);
                });
        logger.debug("codeTexts: " + codeTexts);
        logger.debug("constants: " + constants);
        TextAnalysis textAnalysis = new TextAnalysis();
        String sensitiveTag = textAnalysis.analyze(codeTexts, false);
        logger.debug("text2Path: " + textAnalysis.getText2Path());


        if (DUMP_VERBOSE) {
            if (!sensitiveTag.isEmpty()) {
                logger.debug("Sensitive Tag: {}", sensitiveTag);
                writer.print("Sensitive Tag: ");
                writer.print(sensitiveTag);
                writer.println();
            }
            printCollectedTexts(writer, codeTexts, constants);
        }

        printPaths(sink, textAnalysis, writer);

        writer.flush();
        writer.close();

        if (resultFile.exists() && textAnalysis.getText2Path().isEmpty()) {
            logger.debug("No information found for sink. Deleting log file.");
            resultFile.delete();
        }
    }

    private static void clearSinksFromReportFolder() throws IOException {
        try (Stream<Path> pathStream = Files.walk(Path.of(REPORT_FOLDER))) {
            pathStream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .filter(path -> path.getAbsolutePath().matches(".*[0-9]*\\.[a-zA-Z]*\\.txt"))
                    .forEach(File::delete);
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

    private static void printPaths(DiscoveredSink sink, TextAnalysis textAnalysis, PrintWriter writer) {
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

    private static void printHeader(DiscoveredSink sink, PrintWriter writer) {
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

    public static void collectTextsForNode(TypingNode node, TypingGraph graph, Map<String, List<Statement>> texts,
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
    private static void collectTextsForFields(TypingNode node, TypingGraph graph, Map<String, List<Statement>> texts,
                                              Set<Integer> constants) {
        TypingRecord record = graph.getTypingRecord(node.getGraphNodeId());
        if (record == null) {
            return;
        }
        TextForFieldsCollector collector = new TextForFieldsCollector(graph, record, texts, constants);
        collector.collect(true);
        collector.collect(false);
    }

}