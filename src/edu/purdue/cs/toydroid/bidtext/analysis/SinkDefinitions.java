package edu.purdue.cs.toydroid.bidtext.analysis;

import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import edu.purdue.cs.toydroid.utils.WalaUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SinkDefinitions {
    private final static String SINK_FILE = "dat/Sinks.txt";
    public final static String SEPARATOR = " ";
    public static final String COMMENT_PREFIX = "#";
    private static final Logger logger = LogManager.getLogger(SinkDefinitions.class);

    private static boolean sinksCollected = false;

    private static final Map<String, SinkDefinition> sig2SinkDefinitions = new HashMap<>();

    private static void collectPredefinedSinks() {
        if (sinksCollected) {
            return;
        }
        sinksCollected = true;
        sig2SinkDefinitions.clear();
        try (BufferedReader reader = new BufferedReader(new FileReader(SINK_FILE))) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) {
                    continue;
                }
                String[] parts = line.split(SEPARATOR);
                if (parts.length < 3) {
                    logger.warn("Invalid SINK definition: {}", line);
                    continue;
                }
                String sig = parts[1];
                if (sig2SinkDefinitions.containsKey(sig)) {
                    logger.warn("Duplicate SINK definition: {}", line);
                    continue;
                }

                SinkDefinition sinkDefinition = SinkDefinition.parse(parts);
                sig2SinkDefinitions.put(sig, sinkDefinition);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read sink definition file", e);
        }


        logger.info("{} predefined sinks are collected.", sig2SinkDefinitions.size());
    }

    public static SinkDefinition getSinkDefinition(SSAAbstractInvokeInstruction instruction) {
        collectPredefinedSinks();
        String signature = WalaUtil.getSignature(instruction);
        return sig2SinkDefinitions.get(signature);
    }

    public static boolean matchesSinkDefinition(SSAAbstractInvokeInstruction instruction) {
        collectPredefinedSinks();
        String signature = WalaUtil.getSignature(instruction);
        return sig2SinkDefinitions.containsKey(signature);
    }

    public record SinkDefinition(String tag, List<Integer> indicesOfInterestingParameters) {
        private static SinkDefinition parse(String[] partsOfDefinitionLine) {
            String tag = partsOfDefinitionLine[0];
            // index 1 is signature
            List<Integer> interestingArgs = new ArrayList<>();
            for (int i = 2; i < partsOfDefinitionLine.length; i++) {
                interestingArgs.add(Integer.parseInt(partsOfDefinitionLine[i]));
            }
            return new SinkDefinition(tag, interestingArgs);
        }
    }
}
