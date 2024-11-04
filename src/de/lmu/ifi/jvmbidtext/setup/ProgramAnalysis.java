package de.lmu.ifi.jvmbidtext.setup;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import de.lmu.ifi.jvmbidtext.analysis.AnalysisUtil;
import de.lmu.ifi.jvmbidtext.utils.WalaUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProgramAnalysis implements Callable<ProgramAnalysis> {

    // if != null, all other entrypoints are skipped
    private static final String DEBUG___ONLY_ANALYZE_THIS_ENTRYPOINT = null;

    private static final Logger logger = LogManager.getLogger(ProgramAnalysis.class);
    private final AtomicBoolean taskTimeout = new AtomicBoolean(false);
    private final String pathToJarOrClassesRootFolder;
    private AnalysisCache cache;
    private ClassHierarchy classHierarchy;
    private Set<Entrypoint> entrypoints;

    public ProgramAnalysis(String pathToJarOrClassesRootFolder) {
        this.pathToJarOrClassesRootFolder = pathToJarOrClassesRootFolder;
    }

    public void signalTimeout() {
        taskTimeout.set(true);
    }

    @Override
    public ProgramAnalysis call() throws Exception {
        initialize();
        analyze();
        return this;
    }

    private void initialize() throws Exception {
        cache = new AnalysisCacheImpl();
        CustomClassHierarchyFactory customClassHierarchyFactory =                new CustomClassHierarchyFactory();
        classHierarchy = customClassHierarchyFactory.make(pathToJarOrClassesRootFolder, cache);
        Optional<Set<IMethod>> springControllerHandlerMethods =
                customClassHierarchyFactory.getSpringControllerHandlerMethods();
        WalaUtil.setClassHierarchy(classHierarchy);

        entrypoints = EntrypointDiscovery.discover(classHierarchy, springControllerHandlerMethods);
        logger.info("Entrypoints: " + entrypoints);
    }

    private void analyze() throws Exception {
        int entrypointCounter = 1;

        for (Entrypoint entrypoint : entrypoints) {
            if (DEBUG___ONLY_ANALYZE_THIS_ENTRYPOINT != null &&
                    !entrypoint.getMethod().getName().toString().equals(DEBUG___ONLY_ANALYZE_THIS_ENTRYPOINT)) {
                continue;
            }
            String entrypointSignature = entrypoint.getMethod().getSignature();
            logger.info("Process entrypoint ({}/{}) {}", entrypointCounter, entrypoints.size(), entrypointSignature);
            entrypointCounter++;
            SingleEntryPointAnalysis epAnalysis =
                    new SingleEntryPointAnalysis(entrypoint, classHierarchy.getScope(), classHierarchy, cache,
                            taskTimeout);
            // for now SingleEntryPointAnalysis dumps its results to static util class AnalysisUtil
        }

        AnalysisUtil.dumpTextForSinks();
    }
}
