package edu.purdue.cs.toydroid.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

public class AnalysisConfig {
    private final static String SINK_FILE = "dat/Sinks.txt";
    private final static String SOURCE_FILE = "dat/Sources.txt";
    public final static String SEPERATOR = ",";
    public static final String COMMENT_PREFIX = "#";
    private static Logger logger = LogManager.getLogger(AnalysisConfig.class);

    private static boolean sinkCollected = false;
    private static boolean srcCollected = false;

    private static boolean DEBUG = false;
    /**
     * signature -> X, where X has format "x1,x2,..." denoting all possible
     * parameter locations.
     */
    private static Map<String, String> sig2Sinks;
    private static Map<String, String> sig2Srcs;

    private static void collectPredefinedSinks() {
        if (sinkCollected) {
            return;
        }
        sinkCollected = true;
        sig2Sinks = new HashMap<>();
        if (DEBUG) {
            sig2Sinks.put(
                    "org.apache.http.client.HttpClient.execute(Lorg/apache/http/client/methods/HttpUriRequest;)Lorg/apache/http/HttpResponse;",
                    "HTTP,1");
            sig2Sinks.put(
                    "android.net.http.AndroidHttpClient.execute(Lorg/apache/http/client/methods/HttpUriRequest;)Lorg/apache/http/HttpResponse;",
                    "HTTP,1");
            sig2Sinks.put(
                    "org.apache.http.impl.client.AbstractHttpClient.execute(Lorg/apache/http/client/methods/HttpUriRequest;)Lorg/apache/http/HttpResponse;",
                    "HTTP,1");
        } else {
            BufferedReader reader = null;
            try {
                String line;
                reader = new BufferedReader(new FileReader(SINK_FILE));
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) {
                        continue;
                    }
                    String[] parts = line.split(" ");
                    if (parts.length < 3) {
                        logger.warn("Invalid SINK definition: {}", line);
                        continue;
                    }
                    StringBuilder builder = new StringBuilder(parts[0]);
                    for (int i = 2; i < parts.length; i++) {
                        builder.append(AnalysisConfig.SEPERATOR);
                        builder.append(parts[i]);
                    }
                    sig2Sinks.put(parts[1], builder.toString());
                }
            } catch (Exception e) {
                logger.warn("Error in reading predefined sinks.", e);
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {

                    }
                }
            }
        }
        logger.info("{} predefined sinks are collected.", sig2Sinks.size());
    }

    public static String getPotentialSink(String sig) {
        collectPredefinedSinks();
        return sig2Sinks.get(sig);
    }

    public static String getPotentialSrc(String sig) {
        collectPredefinedSources();
        return sig2Srcs.get(sig);
    }

    private static void collectPredefinedSources() {
        if (srcCollected) {
            return;
        }
        srcCollected = true;
        sig2Srcs = new HashMap<>();

        BufferedReader reader = null;
        try {
            String line;
            reader = new BufferedReader(new FileReader(SOURCE_FILE));
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) {
                    continue;
                }
                String[] parts = line.split(" ");
                if (parts.length < 3) {
                    logger.warn("Invalid SOURCE definition: {}", line);
                    continue;
                }
                StringBuilder builder = new StringBuilder(parts[0]);
                for (int i = 2; i < parts.length; i++) {
                    builder.append(AnalysisConfig.SEPERATOR);
                    builder.append(parts[i]);
                }
                sig2Srcs.put(parts[1], builder.toString());
            }
        } catch (Exception e) {
            logger.warn("Error in reading predefined sources.", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {

                }
            }
        }

        logger.info("{} predefined sources are collected.", sig2Srcs.size());
    }
}
