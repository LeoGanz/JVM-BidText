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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SingleEntryPointAnalysis {
    private static final Logger logger = LogManager.getLogger(SingleEntryPointAnalysis.class);
    private static final int THRESHOLD_IGNORE = 10000000; // 10 million
    private static final int THRESHOLD_CONTEXT_INSENSITIVE = 1000000; // 1 million

    private final AnalysisScope scope;
    private final ClassHierarchy classHierarchy;
    private final AnalysisCache cache;
    private final AtomicBoolean timeout;
    private final Entrypoint entrypoint;
    private CallGraph cg;
    private SDG<InstanceKey> sdg;
    private Graph<Statement> graph;

    public SingleEntryPointAnalysis(Entrypoint ep, AnalysisScope scope, ClassHierarchy classHierarchy,
                                    AnalysisCache cache, AtomicBoolean timeout) throws
            CallGraphBuilderCancelException {
        this.entrypoint = ep;
        this.scope = scope;
        this.classHierarchy = classHierarchy;
        this.cache = cache;
        this.timeout = timeout;
        analyze(); // TODO use futures
    }

    private void analyze() throws CallGraphBuilderCancelException {
        AnalysisOptions options = new AnalysisOptions(scope, Set.of(entrypoint));
        options.setReflectionOptions(AnalysisOptions.ReflectionOptions.NONE);

        if (timeout.get()) {
            return;
        }
        SSAPropagationCallGraphBuilder cgBuilder = Util.makeVanillaNCFABuilder(1, options, cache, classHierarchy);
        buildSDG(options, cgBuilder);
        int numberOfNodesInSDG = sdg.getNumberOfNodes();
        if (numberOfNodesInSDG > THRESHOLD_IGNORE) {
            logger.warn(" * Too big SDG ({}). Ignore it.", numberOfNodesInSDG);
            return;
        } else if (numberOfNodesInSDG > THRESHOLD_CONTEXT_INSENSITIVE) {
            logger.warn(" * Too big SDG ({}). Use context-insensitive builder.", numberOfNodesInSDG);
            if (timeout.get()) {
                return;
            }
            cgBuilder = Util.makeVanillaZeroOneCFABuilder(Language.JAVA, options, cache, classHierarchy);
            buildSDG(options, cgBuilder);
        }

        if (timeout.get()) {
            return;
        }

        pruneSDG();

//        dumpSDG();
        // if (Main.DEBUG) {
        // DotUtil.dotify(g, WalaUtil.makeNodeDecorator(),
        // entrypoint.getMethod().getName().toString() + ".dot", null,
        // null);
        // }
//			 SDGCache sdgCache = new SDGCache(entrypoint);
//			 sdgCache.buildCache(g, cha);
//			 SimplifiedSDG simSDG = SimplifiedSDG.simplify(g, sdgCache);
        // DotUtil.dotify(simSDG, WalaUtil.makeNodeDecorator(),
        // entrypoint.getMethod().getName().toString() + ".s.dot",
        // null, null);
        // if (simSDG == null) {
        // logger.info(" * Empty SDG. No interesting stmt found.");
        // } else {
        logger.info(" * Build TypingGraph");
        TypingGraphUtil.buildTypingGraph(entrypoint, cg, graph, timeout);
        // }
    }

    private void buildSDG(AnalysisOptions options, SSAPropagationCallGraphBuilder cgBuilder) throws
            CallGraphBuilderCancelException {
        logger.info(" * Build CallGraph");
        cg = cgBuilder.makeCallGraph(options, null);
//        logger.debug("CallGraph: " + cg);
        logger.info(" * CG size: {}", cg.getNumberOfNodes());
        // dumpCG(cg);

        logger.info(" * Build SDG");
        sdg = new SDG<>(cg, cgBuilder.getPointerAnalysis(), Slicer.DataDependenceOptions.NO_BASE_NO_EXCEPTIONS,
                Slicer.ControlDependenceOptions.NONE);
    }

    private void pruneSDG() {
        logger.info(" * SDG size before pruning: {}", sdg.getNumberOfNodes());
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
        graph = prunedSdg;
    }

    private void dumpSDG() {
        Map<CGNode, Long> occurrencesOfNodes =
                graph.stream().collect(Collectors.groupingBy(Statement::getNode, Collectors.counting()));
        logger.debug("************** SDG DUMP START ****************");
        logger.debug("Occurrences   x   Method");
        occurrencesOfNodes.forEach((k, v) -> logger.debug(v + "  x  " + k.getMethod().getSignature()));
        logger.debug("************** SDG DUMP END ****************");
    }

}
