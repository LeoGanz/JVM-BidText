package edu.purdue.cs.toydroid.bidtext.graph.construction;

import com.ibm.wala.analysis.stackMachine.AbstractIntStackMachine;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.StaticFieldKey;
import com.ibm.wala.ipa.slicer.HeapStatement.HeapParamCallee;
import com.ibm.wala.ipa.slicer.HeapStatement.HeapReturnCallee;
import com.ibm.wala.ipa.slicer.HeapStatement.HeapReturnCaller;
import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.ssa.*;
import com.ibm.wala.util.graph.Graph;
import edu.purdue.cs.toydroid.bidtext.android.TextLeak;
import edu.purdue.cs.toydroid.bidtext.graph.*;
import edu.purdue.cs.toydroid.bidtext.graph.propagation.Propagator;
import edu.purdue.cs.toydroid.utils.SimpleCounter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class TypingGraphUtil {
    private static final Logger logger = LogManager.getLogger(TypingGraphUtil.class);
    public static Map<Entrypoint, TypingGraph> entry2Graph;
    private static TypingGraph currentTypingGraph;
    private static final Map<SSAGetInstruction, TypingNode> ssaGet2Nodes;
    private static final Map<PointerKey, TypingNode> sFieldHeaps;

    static {
        entry2Graph = new HashMap<>();
        ssaGet2Nodes = new HashMap<>();
        sFieldHeaps = new HashMap<>();
    }

    private static void find(Graph<Statement> sdg, Statement stmt, Set<Statement> left) {
        left.add(stmt);
        Iterator<Statement> iter = sdg.getSuccNodes(stmt);
        while (iter.hasNext()) {
            Statement nstmt = iter.next();
            if (left.contains(nstmt)) {
                continue;
            }
            find(sdg, nstmt, left);
        }
    }

    public static void buildTypingGraph(Entrypoint ep, CallGraph cg, Graph<Statement> sdg) {
        TypingGraph graph = new TypingGraph(ep);
        entry2Graph.put(ep, graph);
        currentTypingGraph = graph;
        logger.info("   - Visit SDG ");
        Map<Statement, SimpleCounter> visitedStatementCount = new HashMap<>();
        int idx = 0;
        for (Statement stmt : sdg) {
            if (stmt.getNode()
                    .getMethod()
                    .getDeclaringClass()
                    .getClassLoader()
                    .getName()
                    .toString()
                    .equals("Application")) {
                logger.debug("    + SDG stmt: {} ## {}", idx, stmt.toString());

//                final Set<Statement> left = new HashSet<>();
//                find(sdg, stmt, left);
//                logger.debug("   LEFT size = {}", left.size());
////                Graph<Statement> g = pruneSDG(sdg, left);
//                try {
//                    DotUtil.dotify(sdg, WalaUtil.makeNodeDecorator(), "5004.dot",
//                            null, null);
//                } catch (WalaException e) {
//                    e.printStackTrace();
//                }
            }
            idx++;
            buildTypingGraphForStmt(cg, sdg, stmt, visitedStatementCount);
            if (TextLeak.taskTimeout) {
                break;
            }
        }

        logger.debug("\nGRAPH NODE TYPING");
        currentTypingGraph.node2Typing.forEach((simpleGraphNode, record) -> {
            logger.debug("  - {} : {}", currentTypingGraph.getNode(simpleGraphNode.nodeId()), record);
        });

        logger.debug("\n\nSUBGRAPHS");
        currentTypingGraph.subGraphs.forEach((cgNode, subgraph) -> {
            logger.debug("  - SG {} (GraphNodeId {})", cgNode, cgNode.getGraphNodeId());
            subgraph.value2Nodes.forEach((val, node) -> {
                logger.debug("        - {} : {} --- {}", val, node,
                        currentTypingGraph.getTypingRecord(node.getGraphNodeId()));
            });
        });


        visitedStatementCount.clear();
        ssaGet2Nodes.clear();
        sFieldHeaps.clear();
        logger.info("   - Process possible incoming fields");
        graph.collectIncomingFields();
        logger.info("   - Revisit TypingGraph for global constants");
        new GlobalConstantStringProcessor(graph).revisitTypingGraph();
        // for debug purpose
        // graph.dumpEntrypointTypingGraph();
        logger.info("   - Update Typing Records for Fields");
        graph.updateFieldTypingRecords();
        logger.info("   - Propagate Typing");
        new Propagator(graph).propagate();
        // clear typing graph at the end - remove unused data for memory efficiency
        graph.clearAtEnd();
    }

    private static void buildTypingGraphForStmt(CallGraph cg, Graph<Statement> sdg, Statement stmt,
                                                Map<Statement, SimpleCounter> visitedStatementCount) {
        // only scan top level stmt
        if (sdg.getPredNodeCount(stmt) != 0
            // || (stmt.getKind() == Kind.HEAP_PARAM_CALLEE && stmt.getNode().equals(cg.getFakeRootNode()))
        ) {
            return;
        }
        ConstructionWorklist worklist = new ConstructionWorklist();
        worklist.add(stmt);
        while (!worklist.isEmpty()) {
            ConstructionWorklist.Item item = worklist.removeFirst();
            buildTypingGraphForStmtBFS(cg, sdg, item, visitedStatementCount, worklist);
        }
    }

    private static void buildTypingGraphForStmtBFS(CallGraph cg, Graph<Statement> sdg, ConstructionWorklist.Item item,
                                                   Map<Statement, SimpleCounter> statementVisitCount,
                                                   ConstructionWorklist worklist) {

        Optional<TypingNode> newCachedNode = handleStatement(cg, sdg, item, worklist);
        if (statementVisited(sdg, item.statement(), statementVisitCount)) {
            return;
        }

        Iterator<Statement> succNodes = sdg.getSuccNodes(item.statement());
        while (succNodes.hasNext()) {
            Statement nextStatement = succNodes.next();
            worklist.add(nextStatement, newCachedNode);
        }
    }

    private static Optional<TypingNode> handleStatement(CallGraph cg, Graph<Statement> sdg,
                                                        ConstructionWorklist.Item item,
                                                        ConstructionWorklist worklist) {
        Statement stmt = item.statement();
        TypingNode cachedNode = item.cachedNode().orElse(null);
        Kind kind = stmt.getKind();
        if (kind != Kind.HEAP_PARAM_CALLEE && kind != Kind.HEAP_PARAM_CALLER && kind != Kind.HEAP_RET_CALLEE &&
                kind != Kind.HEAP_RET_CALLER) {
            logger.debug("      - Handle stmt: {}", stmt.toString());
        }
        return switch (kind) {
            case PHI -> handlePhi((PhiStatement) stmt);
            case NORMAL -> handleNormal((NormalStatement) stmt, cachedNode, worklist);
            case PARAM_CALLER -> handleParamCaller(sdg, (ParamCaller) stmt, worklist);
            case PARAM_CALLEE -> handleParamCallee((ParamCallee) stmt, cachedNode, item.cachedParamCaller());
            case NORMAL_RET_CALLER ->
                    handleNormalRetCaller(sdg, (NormalReturnCaller) stmt, cachedNode, item.cachedNormalStatement());
            case NORMAL_RET_CALLEE -> item.cachedNode();
            case HEAP_RET_CALLEE -> handleHeapRetCallee(cg, (HeapReturnCallee) stmt, cachedNode);
            case HEAP_RET_CALLER -> handleHeapRetCaller(cg, (HeapReturnCaller) stmt);
            case HEAP_PARAM_CALLEE -> handleHeapParamCallee(cg, (HeapParamCallee) stmt);
            default -> Optional.empty();
        };
    }


    /************************************************************/
    /************** Handle Specific WALA Statement **************/

    /**
     * Return True if stmt has more than 1 incoming edges and all these edges
     * have been traversed.
     */
    private static boolean statementVisited(Graph<Statement> sdg, Statement stmt,
                                            Map<Statement, SimpleCounter> visitedStatementsCount) {
        if (sdg.getPredNodeCount(stmt) <= 1) {
            return false;
        }
        SimpleCounter counter = visitedStatementsCount.get(stmt);
        SimpleCounter newCounter = SimpleCounter.increment(counter); // 1 if counter is null, else counter.count++
        if (counter == null) {
            visitedStatementsCount.put(stmt, newCounter);
        }
        if (newCounter.count >= sdg.getPredNodeCount(stmt)) {
            visitedStatementsCount.remove(stmt);
            // TODO for understanding: does this clear the map for the next iteration of the outer loop (processing of all stmts of sdg)?
            // return true; // visited = true; was comment in original code
        }
        return newCounter.count > 1; // TODO shouldn't this be > sdg.getPredNodeCount(stmt)?

    }

    private static Optional<TypingNode> handlePhi(PhiStatement phiStmt) {
        CGNode cgNode = phiStmt.getNode();
        if (cgNode.getMethod().isSynthetic()) {
            return Optional.empty();
        }
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        handleSSAPhi(phiStmt, phiStmt.getPhi(), sg);
        return Optional.empty();
    }

    private static Optional<TypingNode> handleNormal(NormalStatement nstmt, TypingNode cachedNode,
                                                     ConstructionWorklist worklist) {
        CGNode cgNode = nstmt.getNode();
        if (cgNode.getMethod().isSynthetic()) {
            return Optional.empty();
        }
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        SSAInstruction inst = nstmt.getInstruction();

        TypingNode newCachedNode = null;
        if (inst instanceof SSAPutInstruction) {
            newCachedNode = handleSSAPut(nstmt, (SSAPutInstruction) inst, sg);
            // System.err.println("SSAPut: " + inst + " \n\t [" + newCachedNode
            // + "]");
        } else if (inst instanceof SSAGetInstruction) {
            // System.err.println("SSAGet: " + inst + " \n\t [" + cachedNode +
            // "]");
            handleSSAGet(nstmt, (SSAGetInstruction) inst, sg, cachedNode);
        } else if (inst instanceof SSACheckCastInstruction) {
            handleSSACheckCast(nstmt, (SSACheckCastInstruction) inst, sg);
        } else if (inst instanceof SSANewInstruction) {
            handleSSANew((SSANewInstruction) inst, sg);
        } else if (inst instanceof SSAArrayLoadInstruction) {
            handleSSAArrayLoad(nstmt, (SSAArrayLoadInstruction) inst, sg);
        } else if (inst instanceof SSAArrayStoreInstruction) {
            handleSSAArrayStore(nstmt, (SSAArrayStoreInstruction) inst, sg);
        } else if (inst instanceof SSAReturnInstruction) {
            newCachedNode = handleSSAReturn(nstmt, (SSAReturnInstruction) inst, sg, worklist);
        } else if (inst instanceof SSAInstanceofInstruction) {
            handleSSAInstanceof((SSAInstanceofInstruction) inst, sg);
        } else if (inst instanceof SSABinaryOpInstruction) {
            handleSSABinaryOp(nstmt, (SSABinaryOpInstruction) inst, sg);
        } else if (!(inst instanceof SSAAbstractInvokeInstruction)) {
            // System.err.println("Unrecognized Normal Stmt: " + stmt);
        } // invoke is ignored.
        return newCachedNode == null ? Optional.empty() : Optional.of(newCachedNode);
    }

    private static Optional<TypingNode> handleParamCaller(Graph<Statement> sdg, ParamCaller pcstmt,
                                                          ConstructionWorklist worklist) {
        CGNode cgNode = pcstmt.getNode();
        if (cgNode.getMethod().isSynthetic()) {
            return Optional.empty();
        }
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        int nSucc = sdg.getSuccNodeCount(pcstmt);
        if (nSucc == 0) { // API call?
            SSAAbstractInvokeInstruction inst = pcstmt.getInstruction();
            if (!inst.hasDef()) {
                // AnalysisUtil.associateLayout2Activity(inst, cgNode);
                handleSSAInvokeAPI(cgNode, pcstmt, inst, sg);
            }
            // hasDef(): left to be processed in NormalRetCaller?
            return Optional.empty();
        } else { // local call
            int pv = pcstmt.getValueNumber();// recorded for later use in param callee
            worklist.cacheParamCaller(pcstmt);
            TypingNode newCachedNode = sg.findOrCreate(pv);
            return Optional.of(newCachedNode);
        }
    }

    private static Optional<TypingNode> handleParamCallee(ParamCallee pcstmt, TypingNode cachedNode,
                                                          Optional<ParamCaller> cachedStmt) {
        CGNode cgNode = pcstmt.getNode();
        if (cgNode.getMethod().isSynthetic()) {
            return Optional.empty();
        }
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        constructTypingRecordsWithEQConstraintWithCachedStatement(pcstmt, cachedNode, cachedStmt, sg);
        return Optional.empty();
    }

    private static Optional<TypingNode> handleNormalRetCaller(Graph<Statement> sdg, NormalReturnCaller nrc,
                                                              TypingNode cachedNode,
                                                              Optional<NormalStatement> cachedStmt) {
        CGNode cgNode = nrc.getNode();
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        if (sdg.getPredNodeCount(nrc) == 0) {// API call?
            handleSSAInvokeAPI(cgNode, nrc, nrc.getInstruction(), sg);
        } else if (nrc.getInstruction().hasDef()) {
            constructTypingRecordsWithEQConstraintWithCachedStatement(nrc, cachedNode, cachedStmt, sg);
        }
        return Optional.empty();
    }

    private static <StatementWithValueNumber extends Statement & ValueNumberCarrier> void constructTypingRecordsWithEQConstraintWithCachedStatement(
            StatementWithValueNumber statement,
            TypingNode predNode,
            Optional<? extends Statement> predStatement,
            TypingSubGraph sg) {
        if (predNode == null) {
            // TODO Don't error if is entrypoint
            System.err.println(
                    "No predecessor Node is found for Method invocation or return from invocation: " + statement);
        } else {
            TypingNode node = sg.findOrCreate(statement.getValueNumber());
            constructTypingRecordsWithEQConstraintHelper(statement, predNode, node, predStatement);
        }
    }

    private static void constructTypingRecordsWithEQConstraint(NormalStatement stmt, TypingNode lhsNode,
                                                               TypingNode rhsNode) {
        constructTypingRecordsWithEQConstraintHelper(stmt, lhsNode, rhsNode, Optional.empty());
    }

    private static void constructTypingRecordsWithEQConstraintHelper(Statement statement, TypingNode lhsNode,
                                                                     TypingNode rhsNode,
                                                                     Optional<? extends Statement> predStatement) {
        TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(lhsNode.getGraphNodeId());
        TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(rhsNode.getGraphNodeId());
        TypingConstraint forwardConstraint =
                new TypingConstraint(lhsNode.getGraphNodeId(), TypingConstraint.EQ, rhsNode.getGraphNodeId());
        TypingConstraint backwardConstraint = forwardConstraint;
        if (predStatement.isPresent()) {
            forwardConstraint.addPath(predStatement.get());
            // TODO switch order? probably irrelevant for EQ
            backwardConstraint = new TypingConstraint(lhsNode.getGraphNodeId(), TypingConstraint.EQ,
                    rhsNode.getGraphNodeId());
            // reverse the path for backward propagation
            backwardConstraint.addPath(statement);
            backwardConstraint.addPath(predStatement.get());
        }
        forwardConstraint.addPath(statement);
        orec.addForwardTypingConstraint(forwardConstraint);
        nrec.addBackwardTypingConstraint(backwardConstraint);
    }


    private static Optional<TypingNode> handleHeapRetCallee(CallGraph cg, HeapReturnCallee hrc, TypingNode cachedNode) {
        if (cachedNode == null || !cachedNode.isStaticField() || hrc.getNode().equals(cg.getFakeRootNode())) {
            // instance field is immediately used
            return Optional.empty();
        }
        PointerKey location = hrc.getLocation();
        if (location instanceof StaticFieldKey) {
            TypingNode existing = sFieldHeaps.get(location);
            if (!cachedNode.equals(existing)) {
                sFieldHeaps.put(location, cachedNode);
            }
        }
        return Optional.empty();
    }

    private static Optional<TypingNode> handleHeapRetCaller(CallGraph cg, HeapReturnCaller hrc) {
        if (hrc.getNode().equals(cg.getFakeRootNode())) {
            return Optional.empty();
        }
        TypingNode newCachedNode = null;
        PointerKey location = hrc.getLocation();
        if (location instanceof StaticFieldKey) {
            newCachedNode = sFieldHeaps.get(location);
        }
        return newCachedNode == null ? Optional.empty() : Optional.of(newCachedNode);
    }

    private static Optional<TypingNode> handleHeapParamCallee(CallGraph cg, HeapParamCallee hrc) {
        if (hrc.getNode().equals(cg.getFakeRootNode())) {
            return Optional.empty();
        }
        TypingNode newCachedNode = null;
        PointerKey location = hrc.getLocation();
        if (location instanceof StaticFieldKey) {
            newCachedNode = sFieldHeaps.get(location);
        }
        return newCachedNode == null ? Optional.empty() : Optional.of(newCachedNode);
    }

    /************************************************************/
    /************* Handle Specific SSA Instructions *************/
    /************************************************************/
    private static TypingNode handleSSAPut(NormalStatement stmt, SSAPutInstruction inst, TypingSubGraph sg) {
        int val = inst.getVal(); // rhs
        int ref;
        TypingNode valNode = sg.findOrCreate(val);
        TypingNode refNode;
        // currentTypingGraph.
        if (inst.isStatic()) {
            refNode = sg.createStaticFieldNode(inst.getDeclaredField());
        } else {
            ref = inst.getRef();
            refNode = sg.createInstanceFieldNode(ref, inst.getDeclaredField());
        }
        // currentTypingGraph.mergeClass(valNode, refNode);
        constructTypingRecordsWithEQConstraint(stmt, valNode, refNode);

        currentTypingGraph.collectOutgoingField(refNode);
        return refNode;
    }

    private static void handleSSAGet(NormalStatement stmt, SSAGetInstruction inst, TypingSubGraph sg,
                                     TypingNode cachedNode) {
        // if (inst.getDeclaredField()
        // .getName().toString().equals("userMessageForWeb"))
        // return;
        int def = inst.getDef();
        TypingNode defNode = sg.findOrCreate(def);
        if (null != cachedNode) {
            // If a GetField stmt has more than two predecessors, when it is
            // visited at the second time, a node representing the field has
            // been created, which is prevNode here.
            TypingNode prevNode = ssaGet2Nodes.get(inst);
            if (prevNode != null) {
                // currentTypingGraph.mergeClass(cachedNode, prevNode);
                TypingRecord rec = currentTypingGraph.getTypingRecord(cachedNode.getGraphNodeId());
                if (rec == null) {
                    rec = currentTypingGraph.findOrCreateTypingRecord(prevNode.getGraphNodeId());
                    currentTypingGraph.setTypingRecord(cachedNode.getGraphNodeId(), rec);
                } else {
                    TypingConstraint c = new TypingConstraint(prevNode.getGraphNodeId(), TypingConstraint.EQ,
                            cachedNode.getGraphNodeId());
                    TypingRecord prevRec = currentTypingGraph.findOrCreateTypingRecord(prevNode.getGraphNodeId());
                    prevRec.addBackwardTypingConstraint(c);
                    rec.addForwardTypingConstraint(c);
                    // currentTypingGraph.setTypingRecord(
                    // prevNode.getGraphNodeId(), rec);
                }

                currentTypingGraph.unsetPossibleExternalInput(prevNode.getGraphNodeId());
            } else {
                // currentTypingGraph.mergeClass(cachedNode, defNode);
                constructTypingRecordsWithEQConstraint(stmt, cachedNode, defNode);
            }
        } else {
            // some incoming field access from other entrypoint scope
            TypingNode refNode;
            if (inst.isStatic()) {
                refNode = sg.createStaticFieldNode(inst.getDeclaredField());
            } else {
                refNode = sg.createInstanceFieldNode(inst.getRef(), inst.getDeclaredField());
            }
            // currentTypingGraph.mergeClass(refNode, defNode);
            constructTypingRecordsWithEQConstraint(stmt, refNode, defNode);

            currentTypingGraph.setPossibleExternalInput(refNode.getGraphNodeId());
            ssaGet2Nodes.put(inst, refNode);
        }
    }

    private static void handleSSACheckCast(NormalStatement stmt, SSACheckCastInstruction inst, TypingSubGraph sg) {
        int val = inst.getVal(); // rhs
        int ret = inst.getResult(); // lhs
        TypingNode valNode = sg.findOrCreate(val);
        TypingNode retNode = sg.findOrCreate(ret);
        // currentTypingGraph.mergeClass(valNode, retNode);
        constructTypingRecordsWithEQConstraint(stmt, valNode, retNode);
    }

    private static void handleSSANew(SSANewInstruction inst, TypingSubGraph sg) {
        int def = inst.getDef();
        System.out.println("SSANew def: " + def);
        TypingNode defNode = sg.findOrCreate(def);
        defNode.joke();
    }

    private static void handleSSAArrayLoad(NormalStatement stmt, SSAArrayLoadInstruction inst, TypingSubGraph sg) {
        int ref = inst.getArrayRef(); // rhs
        int def = inst.getDef(); // lhs
        TypingNode refNode = sg.findOrCreate(ref);
        TypingNode defNode = sg.findOrCreate(def);
        // currentTypingGraph.mergeClass(refNode, defNode);
        constructTypingRecordsWithEQConstraint(stmt, refNode, defNode);
    }

    private static void handleSSAArrayStore(NormalStatement stmt, SSAArrayStoreInstruction inst, TypingSubGraph sg) {
        int ref = inst.getArrayRef(); // lhs
        int val = inst.getValue(); // rhs
        TypingNode refNode = sg.findOrCreate(ref);
        TypingNode valNode = sg.findOrCreate(val);
        // currentTypingGraph.mergeClass(valNode, refNode);
        constructTypingRecordsWithEQConstraint(stmt, valNode, refNode);
    }


    private static TypingNode handleSSAReturn(NormalStatement stmt, SSAReturnInstruction inst, TypingSubGraph sg,
                                              ConstructionWorklist worklist) {
        if (!inst.returnsVoid()) {
            int ret = inst.getResult();
            worklist.cacheLatestStatement(stmt);
            return sg.findOrCreate(ret);
        }
        return null;
    }

    private static void handleSSAInstanceof(SSAInstanceofInstruction inst, TypingSubGraph sg) {
        int ref = inst.getRef();
        int def = inst.getDef();
        TypingNode refNode = sg.findOrCreate(ref);
        TypingNode defNode = sg.findOrCreate(def);
        // currentTypingGraph.mergeClass(refNode, defNode);
        // TODO: same as "a = b"?
    }

    private static void handleSSABinaryOp(NormalStatement stmt, SSABinaryOpInstruction inst, TypingSubGraph sg) {
        int def = inst.getDef();
        int use0 = inst.getUse(0);
        int use1 = inst.getUse(1);
        TypingNode defNode = sg.findOrCreate(def);
        TypingNode use0Node = sg.findOrCreate(use0);
        TypingNode use1Node = sg.findOrCreate(use1);
        // currentTypingGraph.mergeClass(defNode, use0Node);
        // currentTypingGraph.mergeClass(defNode, use1Node);
        TypingRecord use0Rec = currentTypingGraph.findOrCreateTypingRecord(use0Node.getGraphNodeId());
        TypingRecord use1Rec = currentTypingGraph.findOrCreateTypingRecord(use1Node.getGraphNodeId());
        TypingRecord defRec = currentTypingGraph.findOrCreateTypingRecord(defNode.getGraphNodeId());
        TypingConstraint c0 =
                new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.GE_ASSIGN, use0Node.getGraphNodeId());
        TypingConstraint c1 =
                new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.GE_ASSIGN, use1Node.getGraphNodeId());
        defRec.addBackwardTypingConstraint(c0);
        defRec.addBackwardTypingConstraint(c1);
        use0Rec.addForwardTypingConstraint(c0);
        use1Rec.addForwardTypingConstraint(c1);
        c0.addPath(stmt);
        c1.addPath(stmt);
    }

    private static void handleSSAInvokeAPI(CGNode cgNode, Statement stmt, SSAAbstractInvokeInstruction inst,
                                           TypingSubGraph sg) {
        new InvocationHandler(currentTypingGraph, sg, cgNode, stmt, inst).handle();
    }

    private static void handleSSAPhi(PhiStatement stmt, SSAPhiInstruction inst, TypingSubGraph sg) {
        int def = inst.getDef();
        int nUse = inst.getNumberOfUses();
        TypingNode defNode = sg.findOrCreate(def);
        // logger.info("PHI: {}", inst.toString());
        Set<Integer> dupSet = new HashSet<>();
        TypingRecord defRec = sg.getTypingGraph().findOrCreateTypingRecord(defNode.getGraphNodeId());
        for (int i = 0; i < nUse; i++) {
            // logger.info(" i = {}:{}", i, inst.getUse(i));
            int valueNumber = inst.getUse(i);
            if (valueNumber == AbstractIntStackMachine.TOP) {
                continue;
            }
            if (dupSet.contains(valueNumber)) {
                continue;
            }
            dupSet.add(valueNumber);
            TypingNode useNode = sg.findOrCreate(valueNumber);
            TypingRecord useRec = sg.getTypingGraph().findOrCreateTypingRecord(useNode.getGraphNodeId());
            TypingConstraint c =
                    new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.GE_PHI, useNode.getGraphNodeId());
            defRec.addBackwardTypingConstraint(c);
            useRec.addForwardTypingConstraint(c);
            c.addPath(stmt);
            // currentTypingGraph.mergeClass(useNode, defNode);
        }
    }

}
