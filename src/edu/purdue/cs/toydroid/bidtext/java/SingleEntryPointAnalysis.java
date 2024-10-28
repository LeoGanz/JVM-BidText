package edu.purdue.cs.toydroid.bidtext.java;

import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.callgraph.propagation.StaticFieldKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphSlicer;
import edu.purdue.cs.toydroid.bidtext.graph.construction.TypingGraphUtil;
import edu.purdue.cs.toydroid.utils.SimpleConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SingleEntryPointAnalysis {
    private static final Logger logger = LogManager.getLogger(SingleEntryPointAnalysis.class);

    private final AnalysisScope scope;
    private final ClassHierarchy classHierarchy;
    private final AnalysisCache cache;
    private final AtomicBoolean timeout;
    private final Entrypoint entrypoint;
    private CallGraph cg;

    public SingleEntryPointAnalysis(Entrypoint ep, AnalysisScope scope, ClassHierarchy classHierarchy,
                                    AnalysisCache cache, AtomicBoolean timeout) throws
            CallGraphBuilderCancelException, IOException {
        this.entrypoint = ep;
        this.scope = scope;
        this.classHierarchy = classHierarchy;
        this.cache = cache;
        this.timeout = timeout;
        analyze(); // TODO use futures
    }

    private void analyze() throws CallGraphBuilderCancelException, IOException {
        AnalysisOptions options = new AnalysisOptions(scope, Set.of(entrypoint));
        options.setReflectionOptions(AnalysisOptions.ReflectionOptions.FULL);

        if (timeout.get()) {
            return;
        }
        SSAPropagationCallGraphBuilder cgBuilder = Util.makeVanillaNCFABuilder(1, options, cache, classHierarchy);
        SDG<InstanceKey> sdg = buildSDG(options, cgBuilder);
        if (sdg.getNumberOfNodes() > SimpleConfig.getThresholdSkipEntrypoint()) {
            logger.warn(" * Too big SDG ({}). Ignore it.", sdg.getNumberOfNodes());
            return;
        } else if (sdg.getNumberOfNodes() > SimpleConfig.getThresholdContextInsensitive()) {
            logger.warn(" * Too big SDG ({}). Use context-insensitive builder.", sdg.getNumberOfNodes());
            if (timeout.get()) {
                return;
            }
            cgBuilder = Util.makeVanillaZeroOneCFABuilder(Language.JAVA, options, cache, classHierarchy);
            sdg = buildSDG(options, cgBuilder);
        }

        if (timeout.get()) {
            return;
        }

        Graph<Statement> prunedSdg = pruneSDG(sdg);

        logger.info(" * Build TypingGraph");
        TypingGraphUtil.buildTypingGraph(entrypoint, prunedSdg, cg.getFakeRootNode(), timeout);
    }

    private SDG<InstanceKey> buildSDG(AnalysisOptions options, SSAPropagationCallGraphBuilder cgBuilder) throws
            CallGraphBuilderCancelException {
        logger.info(" * Build CallGraph");
        cg = cgBuilder.makeCallGraph(options, null);
        logger.info(" * CG size: {}", CallGraphStats.getStats(cg));
        logger.info(" * Build SDG");
        return new SDG<>(cg, cgBuilder.getPointerAnalysis(), Slicer.DataDependenceOptions.NO_BASE_NO_EXCEPTIONS,
                Slicer.ControlDependenceOptions.NONE);
    }

    private Graph<Statement> pruneSDG(Graph<Statement> sdg) {
        logger.info(" * SDG size before pruning: {}", sdg.getNumberOfNodes());
//        dumpSDG(sdg);
        Graph<Statement> prunedSdg = GraphSlicer.prune(sdg, t -> {
            Statement.Kind k = t.getKind();
            /*
             * if (t.getNode().equals(sdg.getCallGraph().getFakeRootNode()))
             * { logger.debug("FakeRootNode: {}", k ); return false; } else
             */
            if (k == Statement.Kind.METHOD_ENTRY || k == Statement.Kind.METHOD_EXIT) {
                return false;
            } else if (t.getNode()
                    .getMethod()
                    .getDeclaringClass()
                    .getClassLoader()
                    .getReference()
                    .equals(ClassLoaderReference.Primordial) && (!t.getNode().getMethod().isSynthetic() ||
                    t.getNode()
                            .getMethod()
                            .getDeclaringClass()
                            .getReference()
                            .getName()
                            .toString()
                            .startsWith("Ljava/lang/"))) {
                return false;

            } else if (t.getNode()
                    .getMethod()
                    .getDeclaringClass()
                    .getName()
                    .toString()
                    .startsWith("Landroid/support/v")) {
                return false;
            } else if (k == Statement.Kind.NORMAL) {
                NormalStatement ns = (NormalStatement) t;
                SSAInstruction inst = ns.getInstruction();
                if (inst instanceof SSAAbstractInvokeInstruction) {
                    return false;
                } else if (inst instanceof SSAGetInstruction getInst) {
                    if (getInst.isStatic() && getInst.getDeclaredField()
                            .getDeclaringClass()
                            .getName()
                            .toString()
                            .equals("Ljava/lang/System")) {
                        return false;
                    }
                }
                // } else if (k == Statement.Kind.PARAM_CALLER) {
                // if (sdg.getPredNodeCount(t) == 0) {
                // return false;
                // }
            } else if (t instanceof HeapStatement hs) {
                PointerKey pk = hs.getLocation();
                if (pk instanceof StaticFieldKey sfk) {
                    if (sfk.getField()
                            .getDeclaringClass()
                            .getClassLoader()
                            .getReference()
                            .equals(ClassLoaderReference.Primordial) &&
                            sfk.getField().getDeclaringClass().getName().toString().equals("Ljava/lang/System")) {
                        return false;
                    }
                }
            }

            return true;
        });
        logger.info(" * SDG size after pruning: {}", prunedSdg.getNumberOfNodes());
        dumpSDG(prunedSdg);
        return prunedSdg;
    }

    private void dumpSDG(Graph<Statement> graph) {
        Map<CGNode, Long> occurrencesOfNodes =
                graph.stream().collect(Collectors.groupingBy(Statement::getNode, Collectors.counting()));
        logger.debug("************** SDG DUMP START ****************");
        logger.debug("Occurrences   x   Method");
        occurrencesOfNodes.forEach((k, v) -> logger.debug(v + "  x  " + k.getMethod().getSignature()));
        logger.debug("************** SDG DUMP END ****************");
    }

}
