package edu.purdue.cs.toydroid.bidtext.java;

import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import edu.purdue.cs.toydroid.bidtext.analysis.AnalysisUtil;
import edu.purdue.cs.toydroid.bidtext.graph.TypingGraph;
import edu.purdue.cs.toydroid.utils.SimpleConfig;
import edu.purdue.cs.toydroid.utils.WalaUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class TextLeakAnalysisJava implements Callable<TextLeakAnalysisJava> {

    private static final Logger logger = LogManager.getLogger(TextLeakAnalysisJava.class);
    private static final String SCOPE_DEFINITIONS = "primordial.txt";
    private AtomicBoolean taskTimeout = new AtomicBoolean(false);

    // static String ApkFile =
    // "E:\\x\\y\\AM-com.nitrogen.android-221000000.apk";
    private final String appJar;
    private AnalysisScope scope;
    private ClassHierarchy classHierarchy;
    private Set<Entrypoint> entrypoints;
    private Map<Entrypoint, TypingGraph> ep2Graph;

    public TextLeakAnalysisJava(String appJar) {
        this.appJar = appJar;
        ep2Graph = new HashMap<>();
    }

    public void signalTimeout() {
        taskTimeout.set(true);
    }

    @Override
    public TextLeakAnalysisJava call() throws Exception {
        initialize();
        analyze();
        return this;
    }

    private void initialize() throws Exception {
        String exclusionFilePath = SimpleConfig.getExclusionFile();
        File exclusionsFile = exclusionFilePath != null ? new File(exclusionFilePath) : null;
        scope = AnalysisScopeReader.instance.makeJavaBinaryAnalysisScope(
                appJar, exclusionsFile);
        classHierarchy = ClassHierarchyFactory.make(scope);

        WalaUtil.setClassHierarchy(classHierarchy);
        entrypoints = StreamSupport.stream(Util.makeMainEntrypoints(classHierarchy).spliterator(), false)
                .collect(Collectors.toSet());
    }

    private void analyze() throws Exception {
        AnalysisCache cache = new AnalysisCacheImpl(); //TODO check
        int entrypointCounter = 1;

        for (Entrypoint entrypoint : entrypoints) {
            String entrypointSignature = entrypoint.getMethod().getSignature();
            logger.info("Process entrypoint ({}/{}) {}", entrypointCounter, entrypoints.size(), entrypointSignature);
            entrypointCounter++;
            SingleEntryPointAnalysis epAnalysis =
                    new SingleEntryPointAnalysis(entrypoint, scope, classHierarchy, cache, taskTimeout);
            // for now SingleEntryPointAnalysis dumps its results to static util class AnalysisUtil
        }

        AnalysisUtil.dumpTextForSinks();

    }


}
