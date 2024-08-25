package edu.purdue.cs.toydroid.bidtext.java;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.*;

public class Main {
    private static final long DEFAULT_TIMEOUT = 20;
    private static final Logger logger = LogManager.getLogger(Main.class);
    private static long timeout = DEFAULT_TIMEOUT;

    /**
     * @param args first and only argument should be the path to the system under test. The path can be a path to a
     *             jar file or a folder that is the root of the classes of the system under test, i.e. the folder that
     *             contains subfolders for all the packages specified in class files - do not go up the folder hierarchy
     *             even if the root folder only contains a single subfolder.
     */
    public static void main(String[] args) throws Throwable {
        long analysisStart = System.currentTimeMillis();
        String pathToJarOrClassesRootFolder;
        if (args.length == 1) {
            pathToJarOrClassesRootFolder = args[0];
        } else {
            throw new IllegalArgumentException(
                    "Please specify path to the system under test as the first and only argument.");
        }
        String timeoutSetting = System.getProperty("BidText.Timeout", Long.toString(DEFAULT_TIMEOUT));
        try {
            long temp = Long.parseLong(timeoutSetting);
            if (temp < 5L) {
                logger.warn("Too small TIMEOUT: {} minutes. Use default: {} minutes.", temp, DEFAULT_TIMEOUT);
            } else {
                timeout = temp;
            }
        } catch (Exception e) {
            logger.warn("Invalid TIMEOUT setting: {} minutes. Use default: {} minutes.", timeoutSetting,
                    DEFAULT_TIMEOUT);
        }
        logger.info("Set TIMEOUT as {} minutes.", timeout);
        try {
            doAnalysis(pathToJarOrClassesRootFolder);
        } catch (Throwable e) {
            logger.error("Crashed: {}", e.getMessage());
            throw e;
        }
        long analysisEnd = System.currentTimeMillis();
        String time =
                String.format("%d.%03d", (analysisEnd - analysisStart) / 1000, (analysisEnd - analysisStart) % 1000);
        logger.info("Total Analysis Time: {} seconds.", time);
        long memUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long nKB = memUsed / 1024;
        long rByte = memUsed % 1024;
        long nMB = nKB / 1024;
        long rKB = nKB % 1024;
        long nGB = nMB / 1024;
        long rMB = nMB % 1024;
        String mem;
        if (nGB > 0) {
            mem = String.format("%dG %dM %dK %dByte", nGB, rMB, rKB, rByte);
        } else if (nMB > 0) {
            mem = String.format("%dM %dK %dByte", nMB, rKB, rByte);
        } else {
            mem = String.format("%dK %dByte", nKB, rByte);
        }
        logger.info("Total Time: {} seconds.", time);
        logger.info("Total Memory: {} [{} bytes]", mem, memUsed);
    }

    public static void doAnalysis(String pathToJarOrClassesRootFolder) throws Throwable {
        logger.info("Start Analysis...");
        TextLeakAnalysisJava analysis = new TextLeakAnalysisJava(pathToJarOrClassesRootFolder);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<TextLeakAnalysisJava> future = executor.submit(analysis);
            try {
                try {
                    future.get(timeout, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    logger.error("Analysis interrupted: {}", e.getMessage());
                } catch (ExecutionException e) {
                    logger.error("Analysis failed with an exception: {}", e.getMessage());
                    throw e;
                } catch (TimeoutException e) {
                    analysis.signalTimeout();
                    logger.warn("Analysis Timeout after {} {}!", timeout, TimeUnit.MINUTES);
                }
            } catch (Throwable t) {
                logger.error("Crashed: {}", t.getMessage());
                throw t;
            } finally {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }

        // Stat.dumpStat(ApkFile);
        // if (analysis.hasResult()) {
        // analysis.dumpResult();
        // }
        // analysis.dumpCgStat();
    }
}
