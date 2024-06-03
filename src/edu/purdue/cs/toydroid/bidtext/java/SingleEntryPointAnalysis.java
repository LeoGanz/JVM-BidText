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
import edu.purdue.cs.toydroid.bidtext.graph.TypingGraphUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
        if (timeout.get()) {
            return;
        }

        AnalysisOptions options = new AnalysisOptions(scope, Set.of(entrypoint));
        options.setReflectionOptions(AnalysisOptions.ReflectionOptions.NONE);

        SSAPropagationCallGraphBuilder cgBuilder = Util.makeVanillaNCFABuilder(1, options, cache, classHierarchy);

        // analyzeEntrypoint(entrypoint, cg);
        // if (Stat.statCG(cg)) {
        // continue;
        // }
        if (timeout.get()) {
            return;
        }


        SDG<InstanceKey> sdg = buildSDG(options, cgBuilder);
        int numberOfNodesInSDG = sdg.getNumberOfNodes();
        if (numberOfNodesInSDG > THRESHOLD_IGNORE) {
            logger.warn(" * Too big SDG ({}). Ignore it.", numberOfNodesInSDG);
            return;
        } else if (numberOfNodesInSDG > THRESHOLD_CONTEXT_INSENSITIVE) {
            logger.warn(" * Too big SDG ({}). Use context-insensitive builder.", numberOfNodesInSDG);
            // dumpSDG(pruneSDG(sdg));
            cgBuilder = Util.makeVanillaZeroOneCFABuilder(Language.JAVA, options, cache, classHierarchy);
            sdg = buildSDG(options, cgBuilder);
        }

        if (timeout.get()) {
            return;
        }

        Graph<Statement> g = pruneSDG(sdg);

        // dumpSDG(g);
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
        TypingGraphUtil.buildTypingGraph(entrypoint, cg, g, classHierarchy);
        // }
    }

    private SDG<InstanceKey> buildSDG(AnalysisOptions options, SSAPropagationCallGraphBuilder cgBuilder) throws
            CallGraphBuilderCancelException {
        logger.info(" * Build CallGraph");
        CallGraph cg = cgBuilder.makeCallGraph(options, null);
        logger.info(" * CG size: {}", cg.getNumberOfNodes());
        // dumpCG(cg);

        logger.info(" * Build SDG");
        return new SDG<>(cg, cgBuilder.getPointerAnalysis(), Slicer.DataDependenceOptions.NO_BASE_NO_EXCEPTIONS,
                Slicer.ControlDependenceOptions.NONE);
    }

    private Graph<Statement> pruneSDG(final SDG<InstanceKey> sdg) {
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
                } else if (inst instanceof SSAGetInstruction) {
                    SSAGetInstruction getInst = (SSAGetInstruction) inst;
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
            } else if (t instanceof HeapStatement) {
                HeapStatement hs = (HeapStatement) t;
                PointerKey pk = hs.getLocation();
                if (pk instanceof StaticFieldKey) {
                    StaticFieldKey sfk = (StaticFieldKey) pk;
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
        return prunedSdg;
    }

}
