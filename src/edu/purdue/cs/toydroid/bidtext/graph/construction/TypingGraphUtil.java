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
import edu.purdue.cs.toydroid.bidtext.analysis.AnalysisUtil;
import edu.purdue.cs.toydroid.bidtext.analysis.InterestingNode;
import edu.purdue.cs.toydroid.bidtext.analysis.SpecialModel;
import edu.purdue.cs.toydroid.bidtext.android.TextLeak;
import edu.purdue.cs.toydroid.bidtext.graph.*;
import edu.purdue.cs.toydroid.bidtext.graph.propagation.Propagator;
import edu.purdue.cs.toydroid.utils.SimpleCounter;
import edu.purdue.cs.toydroid.utils.WalaUtil;
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
        // int idx = 0;
        for (Statement stmt : sdg) {
            // logger.info("    + SDG stmt: {} ## {}", idx, stmt.toString());
            // if (idx == 5004) {
            // final Set<Statement> left = new HashSet<Statement>();
            // find(sdg, stmt, left);
            // logger.debug("   LEFT size = {}", left.size());
            // Graph<Statement> g = pruneSDG(sdg, left);
            // try {
            // DotUtil.dotify(g, WalaUtil.makeNodeDecorator(), "5004.dot",
            // null, null);
            // } catch (WalaException e) {
            // e.printStackTrace();
            // }
            // }
            // idx++;
            buildTypingGraphForStmt(cg, sdg, stmt, visitedStatementCount);
            if (TextLeak.taskTimeout) {
                break;
            }
        }


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
        // clear typing graph at the end - remove usused data for memory efficiency
        graph.clearAtEnd();
    }

    private static void buildTypingGraphForStmt(CallGraph cg, Graph<Statement> sdg, Statement stmt,
                                                Map<Statement, SimpleCounter> visitedStatementCount) {
        // only scan top level stmt
        if (0 == sdg.getPredNodeCount(stmt)
            /*
             * && !(stmt.getKind() == Kind.HEAP_PARAM_CALLEE && stmt.getNode()
             * .equals(cg.getFakeRootNode()))
             */) {
            Worklist worklist = new Worklist();
            worklist.add(worklist.item(stmt));
            while (!worklist.isEmpty()) {
                Worklist.Item item = worklist.removeFirst();
                buildTypingGraphForStmtBFS(cg, sdg, item, visitedStatementCount, worklist);
            }
        }
    }

    private static void buildTypingGraphForStmtBFS(CallGraph cg, Graph<Statement> sdg, Worklist.Item item,
                                                   Map<Statement, SimpleCounter> visitedStatementCount,
                                                   Worklist worklist) {

        Optional<TypingNode> newCachedNode = handleStatement(cg, sdg, item, worklist);
        if (!statementVisited(sdg, item.statement(), visitedStatementCount)) {
            Iterator<Statement> succNodes = sdg.getSuccNodes(item.statement());
            while (succNodes.hasNext()) {
                Statement nextStatement = succNodes.next();
                worklist.add(worklist.item(nextStatement, newCachedNode));
            }
        }
    }

    private static Optional<TypingNode> handleStatement(CallGraph cg, Graph<Statement> sdg, Worklist.Item item,
                                                        Worklist worklist) {
        Statement stmt = item.statement();
        TypingNode cachedNode = item.cachedNode().orElse(null);
        Kind kind = stmt.getKind();
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
        boolean visited = false;
        int predNCount = sdg.getPredNodeCount(stmt);
        if (predNCount > 1) {
            SimpleCounter counter = visitedStatementsCount.get(stmt);
            SimpleCounter newCounter = SimpleCounter.increment(counter);
            if (counter == null) {
                visitedStatementsCount.put(stmt, newCounter);
            }
            if (newCounter.count > 1) {
                visited = true;
            }
            if (newCounter.count >= predNCount) {
                // visited = true;
                visitedStatementsCount.remove(stmt);
            }
        }

        return visited;
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

    private static Optional<TypingNode> handleNormal(NormalStatement nstmt, TypingNode cachedNode, Worklist worklist) {
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

    private static Optional<TypingNode> handleParamCaller(Graph<Statement> sdg, ParamCaller pcstmt, Worklist worklist) {
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
                                              Worklist worklist) {
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
        int apiType = AnalysisUtil.tryRecordInterestingNode(inst, sg);

        int nVal, nFreeVar = 0, nConstVar = 0;
        int nParam = inst.getNumberOfPositionalParameters();
        nVal = nParam;
        if (inst.hasDef()) {
            nVal += 1;
        }
        if (nVal == 0) {
            return;
        }
        TypingNode[] freeNodes = new TypingNode[nVal];
        TypingNode[] constNodes = new TypingNode[nVal];
        TypingNode defNode = null, thisNode = null;
        int idx;
        TypingNode node;
        boolean skipThis = false;
        if (inst.isStatic()) {
            for (idx = 0; idx < nParam; idx++) {
                int use = inst.getUse(idx);
                node = sg.findOrCreate(use);
                if (node.isConstant()) {
                    constNodes[nConstVar] = node;
                    nConstVar++;
                } else {
                    freeNodes[nFreeVar] = node;
                    nFreeVar++;
                }
            }
        } else {
            // excluding the "receiver" object first
            for (idx = 0; idx < nParam - 1; idx++) {
                int use = inst.getUse(idx + 1);
                node = sg.findOrCreate(use);
                if (node.isConstant()) {
                    constNodes[nConstVar] = node;
                    nConstVar++;
                } else {
                    freeNodes[nFreeVar] = node;
                    nFreeVar++;
                }
            }
            // freeNodes[nFreeVar++] = sg.findOrCreate(inst.getReceiver());
            thisNode = sg.findOrCreate(inst.getReceiver());
            String thisType = inst.getDeclaredTarget().getDeclaringClass().getName().toString();
            if (thisType.startsWith("Landroid/app") || thisType.startsWith("Landroid/content")) {
                skipThis = true;
            }
        }
        // handle return value
        if (inst.hasDef()) {
            // freeNodes[nFreeVar++] = sg.findOrCreate(inst.getDef());
            defNode = sg.findOrCreate(inst.getDef());
            // logger.info("API: {}", inst.toString(), nFreeVar, nConstVar,
            // freeNodes[nFreeVar-1]);
        }
        TypingRecord thisRec = null;
        TypingRecord defRec = null;
        if (thisNode != null) {
            thisRec = currentTypingGraph.findOrCreateTypingRecord(thisNode.getGraphNodeId());
        }
        if (defNode != null) {
            defRec = currentTypingGraph.findOrCreateTypingRecord(defNode.getGraphNodeId());
        }
        String apiSig = inst.getDeclaredTarget().getSignature();
        if (apiSig.startsWith("java.lang.StringBuilder.append(") ||
                apiSig.startsWith("java.lang.StringBuilder.<init>(Ljava/")) {
            if ((stmt.getKind() == Kind.PARAM_CALLER && ((ParamCaller) stmt).getValueNumber() == inst.getUse(1)) ||
                    stmt.getKind() == Kind.NORMAL_RET_CALLER) {
                handleStringBuilderAppend(stmt, cgNode, defNode, defRec, thisNode, thisRec, sg.find(inst.getUse(1)));
            }
            return;
        } else if (apiSig.startsWith("java.lang.StringBuilder.toString(")) {
            handleStringBuilderToString(stmt, defNode, defRec, thisNode, thisRec);
            return;
        }
        String sig = WalaUtil.getSignature(inst);
        String apiRule = APIPropagationRules.getRule(sig);
        if (apiRule != null) {
            handleAPIByRule(stmt, inst, apiRule, defNode, defRec, sg);
        } else if ((apiRule = APISourceCorrelationRules.getRule(sig)) != null) {
            handleAPISourceByRule(stmt, apiRule, defNode, sg);
        } else {
            int apiConstraint = TypingConstraint.GE;
            if (SpecialModel.isSpecialModel(inst)) {
                // System.err.println(inst);
                apiConstraint = TypingConstraint.GE_UNIDIR;
            }
            for (idx = 0; idx < nFreeVar; idx++) {
                TypingNode pNode = freeNodes[idx];
                TypingRecord pRec = currentTypingGraph.findOrCreateTypingRecord(pNode.getGraphNodeId());
                for (int cdx = 0; cdx < nConstVar; cdx++) {
                    TypingNode cNode = constNodes[cdx];
                    TypingRecord cRec = currentTypingGraph.findOrCreateTypingRecord(cNode.getGraphNodeId());
                    TypingConstraint c =
                            new TypingConstraint(pNode.getGraphNodeId(), TypingConstraint.GE, cNode.getGraphNodeId());
                    c.addPath(stmt);
                    cRec.addForwardTypingConstraint(c);
                }
                if (apiType != 2) {
                    if (thisRec != null && !skipThis) {
                        TypingConstraint c =
                                new TypingConstraint(thisNode.getGraphNodeId(), apiConstraint, pNode.getGraphNodeId());
                        c.addPath(stmt);
                        pRec.addForwardTypingConstraint(c);
                        thisRec.addBackwardTypingConstraint(c);
                    } else if (defRec != null) {
                        TypingConstraint c =
                                new TypingConstraint(defNode.getGraphNodeId(), apiConstraint, pNode.getGraphNodeId());
                        c.addPath(stmt);
                        pRec.addForwardTypingConstraint(c);
                        defRec.addBackwardTypingConstraint(c);
                    }
                }
            }
            if (nFreeVar == 0 && apiType != 2) {
                for (int cdx = 0; cdx < nConstVar; cdx++) {
                    TypingNode cNode = constNodes[cdx];
                    TypingRecord cRec = currentTypingGraph.findOrCreateTypingRecord(cNode.getGraphNodeId());
                    if (thisRec != null && !skipThis) {
                        TypingConstraint c = new TypingConstraint(thisNode.getGraphNodeId(), TypingConstraint.GE,
                                cNode.getGraphNodeId());
                        c.addPath(stmt);
                        cRec.addForwardTypingConstraint(c);
                    } else if (defRec != null) {
                        TypingConstraint c = new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.GE,
                                cNode.getGraphNodeId());
                        c.addPath(stmt);
                        cRec.addForwardTypingConstraint(c);
                        // defRec.addBackwardTypingConstraint(c);
                    }
                }
            }
            if (thisRec != null && !skipThis && defRec != null && apiType != 2) {
                TypingConstraint c =
                        new TypingConstraint(defNode.getGraphNodeId(), apiConstraint, thisNode.getGraphNodeId());
                c.addPath(stmt);
                thisRec.addForwardTypingConstraint(c);
                // if (apiConstraint != TypingConstraint.GE_UNIDIR)
                defRec.addBackwardTypingConstraint(c);
            }
        }
        InterestingNode sink = AnalysisUtil.getLatestInterestingNode();
        if (apiType == 2 && sink != null) {
            Iterator<TypingNode> sinkArgs = sink.iterateInterestingArgs();
            while (sinkArgs.hasNext()) {
                TypingNode t = sinkArgs.next();
                t.markSpecial();
            }
        }
    }

    private static void handleStringBuilderAppend(Statement stmt, CGNode cgNode, TypingNode defNode,
                                                  TypingRecord defRec, TypingNode thisNode, TypingRecord thisRec,
                                                  TypingNode paramNode) {
        TypingRecord paramRec = currentTypingGraph.findOrCreateTypingRecord(paramNode.getGraphNodeId());
        if (paramNode.isConstant()) {
            String str;
            if (paramNode.isString()) {
                str = cgNode.getIR().getSymbolTable().getStringValue(paramNode.value);
            } else {
                str = cgNode.getIR().getSymbolTable().getConstantValue(paramNode.value).toString();
            }
            thisRec.addTypingAppend(str);
        } else {
            thisRec.addTypingAppend(paramNode.getGraphNodeId());
        }
        TypingConstraint c =
                new TypingConstraint(thisNode.getGraphNodeId(), TypingConstraint.GE_APPEND, paramNode.getGraphNodeId());
        paramRec.addForwardTypingConstraint(c);
        thisRec.addBackwardTypingConstraint(c);
        if (defRec != null) {
            defRec.addTypingAppend(thisRec);
            c = new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.GE_APPEND, paramNode.getGraphNodeId());
            c.addPath(stmt);
            paramRec.addForwardTypingConstraint(c);
            defRec.addBackwardTypingConstraint(c);
            c = new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.GE_APPEND, thisNode.getGraphNodeId());
            c.addPath(stmt);
            thisRec.addForwardTypingConstraint(c);
            defRec.addBackwardTypingConstraint(c);
        }
    }

    private static void handleStringBuilderToString(Statement stmt, TypingNode defNode, TypingRecord defRec,
                                                    TypingNode thisNode, TypingRecord thisRec) {
        if (defRec != null) {
            TypingConstraint c =
                    new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.EQ, thisNode.getGraphNodeId());
            c.addPath(stmt);
            thisRec.addForwardTypingConstraint(c);
            defRec.addBackwardTypingConstraint(c);
        }

    }

    private static void handleAPIByRule(Statement stmt, SSAAbstractInvokeInstruction inst, String rule,
                                        TypingNode defNode, TypingRecord defRec, TypingSubGraph sg) {
        String[] rules = rule.split(",");
        int[] ruleRep = new int[3];
        TypingNode leftNode = null, rightNode = null;
        TypingRecord leftRec = null, rightRec = null;
        for (String s : rules) {
            String R = s.trim();
            ruleRep[1] = APIPropagationRules.NOTHING;
            APIPropagationRules.parseRule(R, ruleRep);
            if (ruleRep[1] == APIPropagationRules.NOTHING) {
                continue;
            }
            int leftIdx = ruleRep[0];
            int rightIdx = ruleRep[2];
            int op = ruleRep[1];
            if (leftIdx == -1) {
                leftNode = defNode;
                leftRec = defRec;
            } else if (leftIdx < inst.getNumberOfUses()) {
                int use = inst.getUse(leftIdx);
                leftNode = sg.find(use);
                if (leftNode != null) {
                    leftRec = currentTypingGraph.findOrCreateTypingRecord(leftNode.getGraphNodeId());
                }
            }
            if (rightIdx == -1) {
                rightNode = defNode;
                rightRec = defRec;
            } else if (rightIdx < inst.getNumberOfUses()) {
                int use = inst.getUse(rightIdx);
                rightNode = sg.find(use);
                if (rightNode != null) {
                    rightRec = currentTypingGraph.findOrCreateTypingRecord(rightNode.getGraphNodeId());
                }
            }
            if (leftRec != null && rightRec != null) {
                TypingConstraint c;
                if (op == APIPropagationRules.LEFT_PROP) {
                    c = new TypingConstraint(leftNode.getGraphNodeId(), TypingConstraint.GE,
                            rightNode.getGraphNodeId());
                    c.addPath(stmt);
                    rightRec.addForwardTypingConstraint(c);
                } else if (op == APIPropagationRules.RIGHT_PROP) {
                    c = new TypingConstraint(rightNode.getGraphNodeId(), TypingConstraint.GE,
                            leftNode.getGraphNodeId());
                    c.addPath(stmt);
                    leftRec.addForwardTypingConstraint(c);
                } else { // dual propagation
                    c = new TypingConstraint(leftNode.getGraphNodeId(), TypingConstraint.GE,
                            rightNode.getGraphNodeId());
                    c.addPath(stmt);
                    leftRec.addBackwardTypingConstraint(c);
                    rightRec.addForwardTypingConstraint(c);
                }
            }
        }
    }

    private static void handleAPISourceByRule(Statement stmt, String rule, TypingNode defNode, TypingSubGraph sg) {
        TypingNode fakeNode = sg.createFakeConstantNode();
        TypingRecord fakeRec = currentTypingGraph.findOrCreateTypingRecord(fakeNode.getGraphNodeId());
        fakeRec.addTypingText(rule);
        TypingConstraint c =
                new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.GE_UNIDIR, fakeNode.getGraphNodeId());
        c.addPath(stmt);
        fakeRec.addForwardTypingConstraint(c);
    }

    private static void handleSSAPhi(PhiStatement stmt, SSAPhiInstruction inst, TypingSubGraph sg) {
        int def = inst.getDef();
        int nUse = inst.getNumberOfUses();
        TypingNode defNode = sg.findOrCreate(def);
        // logger.info("PHI: {}", inst.toString());
        Set<Integer> dupSet = new HashSet<>();
        TypingRecord defRec = sg.typingGraph.findOrCreateTypingRecord(defNode.getGraphNodeId());
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
            TypingRecord useRec = sg.typingGraph.findOrCreateTypingRecord(useNode.getGraphNodeId());
            TypingConstraint c =
                    new TypingConstraint(defNode.getGraphNodeId(), TypingConstraint.GE_PHI, useNode.getGraphNodeId());
            defRec.addBackwardTypingConstraint(c);
            useRec.addForwardTypingConstraint(c);
            c.addPath(stmt);
            // currentTypingGraph.mergeClass(useNode, defNode);
        }
    }

}
