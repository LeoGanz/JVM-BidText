package edu.purdue.cs.toydroid.bidtext.graph;

import com.ibm.wala.analysis.stackMachine.AbstractIntStackMachine;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.StaticFieldKey;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.slicer.HeapStatement.HeapParamCallee;
import com.ibm.wala.ipa.slicer.HeapStatement.HeapReturnCallee;
import com.ibm.wala.ipa.slicer.HeapStatement.HeapReturnCaller;
import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.ssa.*;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.graph.Graph;
import edu.purdue.cs.toydroid.bidtext.analysis.AnalysisUtil;
import edu.purdue.cs.toydroid.bidtext.analysis.InterestingNode;
import edu.purdue.cs.toydroid.bidtext.analysis.SpecialModel;
import edu.purdue.cs.toydroid.bidtext.android.TextLeak;
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
    private static ClassHierarchy cha;
    /************************************************************/
    private static Statement cachedStmt = null;

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

    public static void buildTypingGraph(Entrypoint ep, CallGraph cg, Graph<Statement> sdg, ClassHierarchy _cha) {
        cha = _cha;
        TypingGraph graph = new TypingGraph(ep);
        entry2Graph.put(ep, graph);
        currentTypingGraph = graph;
        logger.info("   - Visit SDG ");
        Map<Statement, SimpleCounter> visited = new HashMap<>();
        int idx = 0;
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
            // // TODO Auto-generated catch block
            // e.printStackTrace();
            // }
            // }
            idx++;
            buildTypingGraphForStmt(cg, sdg, stmt, visited);
            if (TextLeak.taskTimeout) {
                break;
            }
        }

        visited.clear();
        ssaGet2Nodes.clear();
        sFieldHeaps.clear();
        logger.info("   - Process possible incoming fields");
        graph.collectIncomingFields();
        logger.info("   - Revisit TypingGraph for global constants");
        revisitTypingGraph(graph);
        // for debug purpose
        // graph.dumpEntrypointTypingGraph();
        logger.info("   - Update Typing Records for Fields");
        graph.updateFieldTypingRecords();
        logger.info("   - Propagate Typing");
        new Propagator(graph).propagate();
        // clear typing graph at the end - remove usused data for memory
        // efficiency
        graph.clearAtEnd();
    }

    // ///////////////////////////
    private static void revisitTypingGraph(TypingGraph graph) {
        Iterator<CGNode> allCGNodes = graph.iterateAllCGNodes();
        while (allCGNodes.hasNext()) {
            CGNode cgNode = allCGNodes.next();
            TypingSubGraph sg = graph.findOrCreateSubGraph(cgNode);
            revisitPotentialGString(cgNode, sg);
        }
    }

    private static void buildTypingGraphForStmt(CallGraph cg, Graph<Statement> sdg, Statement stmt,
                                                Map<Statement, SimpleCounter> visited) {
        // only scan top level stmt
        if (0 == sdg.getPredNodeCount(stmt)
            /*
             * && !(stmt.getKind() == Kind.HEAP_PARAM_CALLEE && stmt.getNode()
             * .equals(cg.getFakeRootNode()))
             */) {
            List<Object> worklist = new LinkedList<Object>();
            worklist.add(stmt);
            while (!worklist.isEmpty()) {
                Object obj = worklist.removeFirst();
                Statement nextStmt;
                TypingNode cachedNode = null;
                if (obj instanceof TypingNode) {
                    cachedNode = (TypingNode) obj;
                    nextStmt = (Statement) worklist.removeFirst();
                } else {
                    nextStmt = (Statement) obj;
                }
                // logger.debug("      * {}", nextStmt.toString());
                buildTypingGraphForStmtBFS(cg, sdg, nextStmt, visited, cachedNode, worklist);
            }
            // buildTypingGraphForStmtDFS(cg, sdg, stmt, visited, null);
        }
    }

    private static void buildTypingGraphForStmtBFS(CallGraph cg, Graph<Statement> sdg, Statement stmt,
                                                   Map<Statement, SimpleCounter> visited, TypingNode cachedNode,
                                                   List<Object> worklist) {

        TypingNode newCachedNode = handleStatement(cg, sdg, stmt, cachedNode);
        if (!statementVisited(sdg, stmt, visited)) {
            Iterator<Statement> iter = sdg.getSuccNodes(stmt);
            while (iter.hasNext()) {
                stmt = iter.next();
                if (newCachedNode != null) {
                    worklist.add(newCachedNode);
                }
                worklist.add(stmt);
            }
        }
    }

    private static TypingNode handleStatement(CallGraph cg, Graph<Statement> sdg, Statement stmt,
                                              TypingNode cachedNode) {
        Kind kind = stmt.getKind();
        return switch (kind) {
            case PHI -> {
                handlePhi(stmt);
                yield null;
            }
            case NORMAL -> handleNormal(stmt, cachedNode);
            case PARAM_CALLER -> handleParamCaller(sdg, stmt);
            case PARAM_CALLEE -> {
                handleParamCallee(stmt, cachedNode);
                yield null;
            }
            case NORMAL_RET_CALLER -> {
                handleNormalRetCaller(sdg, stmt, cachedNode);
                yield null;
            }
            case NORMAL_RET_CALLEE -> cachedNode;
            // System.err.println("NRCallee: " + cachedNode);
            case HEAP_RET_CALLEE -> {
                handleHeapRetCallee(cg, stmt, cachedNode);
                yield null;
            }
            case HEAP_RET_CALLER -> handleHeapRetCaller(cg, stmt);
            case HEAP_PARAM_CALLEE -> handleHeapParamCallee(cg, stmt);
            default -> null;
        };
    }

    @Deprecated
    private static void buildTypingGraphForStmtDFS(CallGraph cg, Graph<Statement> sdg, Statement stmt,
                                                   Map<Statement, SimpleCounter> visited, TypingNode cachedNode) {
        TypingNode newCachedNode = handleStatement(cg, sdg, stmt, cachedNode);
        // if (!visited.contains(stmt)) {
        if (!statementVisited(sdg, stmt, visited)) {
            // visited.add(stmt);
            if (stmt.getKind() == Statement.Kind.NORMAL) {
                NormalStatement ns = (NormalStatement) stmt;
                if ("arraystore 47[51] = 49".equals(ns.getInstruction().toString())) {
                    logger.debug("     arraystore 47[51] = 49 ::: {}", sdg.getPredNodeCount(stmt));
                    Iterator<Statement> i = sdg.getPredNodes(stmt);
                    while (i.hasNext()) {
                        logger.debug("   {}", i.next().toString());
                    }
                }
            }
            Iterator<Statement> iter = sdg.getSuccNodes(stmt);
            while (iter.hasNext()) {
                stmt = iter.next();
                buildTypingGraphForStmtDFS(cg, sdg, stmt, visited, newCachedNode);
            }
        }
    }

    /************************************************************/
    /************** Handle Specific WALA Statement **************/

    /**
     * Return True if stmt has more than 1 incoming edges and all these edges
     * have been traversed.
     */
    private static boolean statementVisited(Graph<Statement> sdg, Statement stmt,
                                            Map<Statement, SimpleCounter> visited) {
        boolean v = false;
        int predNCount = sdg.getPredNodeCount(stmt);
        if (predNCount > 1) {
            SimpleCounter counter = visited.get(stmt);
            SimpleCounter newCounter = SimpleCounter.increment(counter);
            if (counter == null) {
                visited.put(stmt, newCounter);
            }
            if (newCounter.count > 1) {
                v = true;
            }
            if (newCounter.count >= predNCount) {
                // v = true;
                visited.remove(stmt);
            }
        }

        return v;
    }

    private static void handlePhi(Statement stmt) {
        CGNode cgNode = stmt.getNode();
        if (cgNode.getMethod().isSynthetic()) {
            return;
        }
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        PhiStatement phiStmt = (PhiStatement) stmt;
        handleSSAPhi(phiStmt, phiStmt.getPhi(), sg);
    }

    private static TypingNode handleNormal(Statement stmt, TypingNode cachedNode) {
        CGNode cgNode = stmt.getNode();
        TypingNode newCachedNode = null;
        if (cgNode.getMethod().isSynthetic()) {
            return newCachedNode;
        }
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        NormalStatement nstmt = (NormalStatement) stmt;
        SSAInstruction inst = nstmt.getInstruction();

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
            newCachedNode = handleSSAReturn(nstmt, (SSAReturnInstruction) inst, sg);
        } else if (inst instanceof SSAInstanceofInstruction) {
            handleSSAInstanceof((SSAInstanceofInstruction) inst, sg);
        } else if (inst instanceof SSABinaryOpInstruction) {
            handleSSABinaryOp(nstmt, (SSABinaryOpInstruction) inst, sg);
        } else if (!(inst instanceof SSAAbstractInvokeInstruction)) {
            // System.err.println("Unrecognized Normal Stmt: " + stmt);
        } // invoke is ignored.
        return newCachedNode;
    }

    private static TypingNode handleParamCaller(Graph<Statement> sdg, Statement stmt) {
        CGNode cgNode = stmt.getNode();
        TypingNode newCachedNode = null;
        if (cgNode.getMethod().isSynthetic()) {
            return newCachedNode;
        }
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        ParamCaller pcstmt = (ParamCaller) stmt;
        int nSucc = sdg.getSuccNodeCount(pcstmt);
        if (nSucc == 0) { // API call?
            SSAAbstractInvokeInstruction inst = pcstmt.getInstruction();
            if (!inst.hasDef()) {
                // AnalysisUtil.associateLayout2Activity(inst, cgNode);
                handleSSAInvokeAPI(cgNode, stmt, inst, sg);
            }
            // hasDef(): left to be processed in NormalRetCaller?
        } else { // local call
            int pv = pcstmt.getValueNumber();// recorded for later use in param
            // callee
            newCachedNode = sg.findOrCreate(pv);
            cachedStmt = stmt;
        }
        return newCachedNode;
    }

    private static void handleParamCallee(Statement stmt, TypingNode cachedNode) {
        CGNode cgNode = stmt.getNode();
        if (cgNode.getMethod().isSynthetic()) {
            return;
        }
        ParamCallee pcstmt = (ParamCallee) stmt;
        if (cachedNode == null) {
            // System.err.println("Unrecognized ParamCallee <No Cached Node Found>: "
            // + stmt);
        } else {
            TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
            int pv = pcstmt.getValueNumber();
            TypingNode paramNode = sg.findOrCreate(pv);
            // currentTypingGraph.mergeClass(cachedNode, paramNode);
            TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(cachedNode.getGraphNodeId());
            TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(paramNode.getGraphNodeId());
            TypingConstraint c =
                    new TypingConstraint(paramNode.getGraphNodeId(), TypingConstraint.EQ, cachedNode.getGraphNodeId());
            TypingConstraint bc = c;
            if (cachedStmt != null) {
                c.addPath(cachedStmt);
                bc = new TypingConstraint(paramNode.getGraphNodeId(), TypingConstraint.EQ, cachedNode.getGraphNodeId());
                // reverse the path for backward propagation
                bc.addPath(stmt);
                bc.addPath(cachedStmt);
            }
            c.addPath(stmt);
            orec.addForwardTypingConstraint(c);
            nrec.addBackwardTypingConstraint(bc);
            cachedStmt = null;
        }
    }

    private static void handleNormalRetCaller(Graph<Statement> sdg, Statement stmt, TypingNode cachedNode) {
        NormalReturnCaller nrc = (NormalReturnCaller) stmt;
        CGNode cgNode = nrc.getNode();
        TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
        if (sdg.getPredNodeCount(stmt) == 0) {// API call?
            handleSSAInvokeAPI(cgNode, stmt, nrc.getInstruction(), sg);
        } else if (nrc.getInstruction().hasDef()) {
            if (cachedNode == null) {
                System.err.println("No Return Object is found for NormalRetCaller: " + nrc);
            } else {
                int ret = nrc.getValueNumber();
                TypingNode retNode = sg.findOrCreate(ret);
                // currentTypingGraph.mergeClass(cachedNode, retNode);
                TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(cachedNode.getGraphNodeId());
                TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(retNode.getGraphNodeId());
                TypingConstraint c = new TypingConstraint(retNode.getGraphNodeId(), TypingConstraint.EQ,
                        cachedNode.getGraphNodeId());
                TypingConstraint bc = c;
                if (cachedStmt != null) {
                    c.addPath(cachedStmt);
                    bc = new TypingConstraint(retNode.getGraphNodeId(), TypingConstraint.EQ,
                            cachedNode.getGraphNodeId());
                    bc.addPath(stmt);
                    bc.addPath(cachedStmt);
                }
                c.addPath(stmt);
                orec.addForwardTypingConstraint(c);
                nrec.addBackwardTypingConstraint(bc);
                // cachedStmt = null;
            }
        }
    }

    private static void handleHeapRetCallee(CallGraph cg, Statement stmt, TypingNode cachedNode) {
        if (cachedNode == null || !cachedNode.isStaticField() || stmt.getNode().equals(cg.getFakeRootNode())) {
            // instance field is immediately used
            return;
        }
        HeapReturnCallee hrc = (HeapReturnCallee) stmt;
        PointerKey location = hrc.getLocation();
        if (location instanceof StaticFieldKey) {
            TypingNode existing = sFieldHeaps.get(location);
            if (!cachedNode.equals(existing)) {
                sFieldHeaps.put(location, cachedNode);
            }
        }
    }

    private static TypingNode handleHeapRetCaller(CallGraph cg, Statement stmt) {
        if (stmt.getNode().equals(cg.getFakeRootNode())) {
            return null;
        }
        TypingNode newCacheNode = null;
        HeapReturnCaller hrc = (HeapReturnCaller) stmt;
        PointerKey location = hrc.getLocation();
        if (location instanceof StaticFieldKey) {
            newCacheNode = sFieldHeaps.get(location);
        }
        return newCacheNode;
    }

    private static TypingNode handleHeapParamCallee(CallGraph cg, Statement stmt) {
        if (stmt.getNode().equals(cg.getFakeRootNode())) {
            return null;
        }
        TypingNode newCacheNode = null;
        HeapParamCallee hrc = (HeapParamCallee) stmt;
        PointerKey location = hrc.getLocation();
        if (location instanceof StaticFieldKey) {
            newCacheNode = sFieldHeaps.get(location);
        }
        return newCacheNode;
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
        constructTypingRecordsWithEQConstraint(stmt, refNode, valNode);

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
                constructTypingRecordsWithEQConstraint(stmt, defNode, cachedNode);
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
            constructTypingRecordsWithEQConstraint(stmt, defNode, refNode);

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
        constructTypingRecordsWithEQConstraint(stmt, retNode, valNode);
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
        constructTypingRecordsWithEQConstraint(stmt, defNode, refNode);
    }

    private static void handleSSAArrayStore(NormalStatement stmt, SSAArrayStoreInstruction inst, TypingSubGraph sg) {
        int ref = inst.getArrayRef(); // lhs
        int val = inst.getValue(); // rhs
        TypingNode refNode = sg.findOrCreate(ref);
        TypingNode valNode = sg.findOrCreate(val);
        // currentTypingGraph.mergeClass(valNode, refNode);
        constructTypingRecordsWithEQConstraint(stmt, refNode, valNode);
    }

    private static void constructTypingRecordsWithEQConstraint(NormalStatement stmt, TypingNode lhsNode,
                                                               TypingNode rhsNode) {
        TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(rhsNode.getGraphNodeId());
        TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(lhsNode.getGraphNodeId());
        TypingConstraint c =
                new TypingConstraint(rhsNode.getGraphNodeId(), TypingConstraint.EQ, lhsNode.getGraphNodeId());
        c.addPath(stmt);
        orec.addForwardTypingConstraint(c);
        nrec.addBackwardTypingConstraint(c);
    }

    private static TypingNode handleSSAReturn(NormalStatement stmt, SSAReturnInstruction inst, TypingSubGraph sg) {
        TypingNode retNode = null;
        if (!inst.returnsVoid()) {
            int ret = inst.getResult();
            retNode = sg.findOrCreate(ret);
            cachedStmt = stmt;
        }
        return retNode;
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
        int apiType = AnalysisUtil.tryRecordInterestingNode(inst, sg, cha);

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
        dupSet = null;
    }

    /************************************************************/
    /****************** Handle Constant String ******************/
    /************************************************************/
    private static void revisitPotentialGString(CGNode cgNode, TypingSubGraph sg) {
        Set<SSAAbstractInvokeInstruction> gStringLoc = sg.potentialGStringLocations();
        if (gStringLoc == null) {
            return;
        }
        IR ir = cgNode.getIR();
        SymbolTable symTable = ir.getSymbolTable();
        SSACFG cfg = ir.getControlFlowGraph();
        SSACFG.BasicBlock bb = cfg.getBasicBlock(2);
        // System.out.println(ir);
        for (SSAAbstractInvokeInstruction inst : gStringLoc) {
            revisitInvokeInstruction(cfg, symTable, inst, sg);
        }
    }

    private static void revisitInvokeInstruction(SSACFG cfg, SymbolTable symTable, SSAAbstractInvokeInstruction inst,
                                                 TypingSubGraph sg) {
        List<TypingNode> potentialGNodes = potentialGStringNode(symTable, inst, sg);
        if (potentialGNodes != null) {
            SSACFG.BasicBlock locatedBB = cfg.getBlockForInstruction(inst.iIndex());
            List<ISSABasicBlock> worklist = new LinkedList<ISSABasicBlock>();
            Set<ISSABasicBlock> storedBBs = new HashSet<ISSABasicBlock>();
            worklist.add(locatedBB);
            forwardObtainBBsInLine(cfg, worklist, storedBBs);
            for (ISSABasicBlock bb : storedBBs) {
                typingGlobalStringInBB(cfg, symTable, bb, potentialGNodes, sg, inst, true);
            }
            worklist.add(locatedBB);
            storedBBs.clear();
            backwardObtainBBsInLine(cfg, worklist, storedBBs);
            storedBBs.remove(locatedBB);
            for (ISSABasicBlock bb : storedBBs) {
                typingGlobalStringInBB(cfg, symTable, bb, potentialGNodes, sg, inst, false);
            }
        }
    }

    private static void forwardObtainBBsInLine(SSACFG cfg, List<ISSABasicBlock> worklist,
                                               Set<ISSABasicBlock> storedBBs) {
        ISSABasicBlock bb;
        if (worklist.isEmpty()) {
            return;
        }
        bb = worklist.removeFirst();
        if (bb.isExitBlock() || cfg.getPredNodeCount(bb) > 1) {
            return;
        }
        storedBBs.add(bb);
        SSAInstruction lastInst = bb.getLastInstruction();
        if (!(lastInst instanceof SSAConditionalBranchInstruction) && !(lastInst instanceof SSASwitchInstruction)) {
            Iterator<ISSABasicBlock> iter = cfg.getSuccNodes(bb);
            while (iter.hasNext()) {
                ISSABasicBlock succ = iter.next();
                if (succ.isCatchBlock() || succ.isExitBlock()) {
                    continue;
                }
                worklist.add(succ);
                break;
            }
        }
        forwardObtainBBsInLine(cfg, worklist, storedBBs);
    }

    private static void backwardObtainBBsInLine(SSACFG cfg, List<ISSABasicBlock> worklist,
                                                Set<ISSABasicBlock> storedBBs) {
        ISSABasicBlock bb;
        if (worklist.isEmpty()) {
            return;
        }
        bb = worklist.removeFirst();
        if (bb.isEntryBlock() || cfg.getPredNodeCount(bb) > 1) {
            return;
        }
        storedBBs.add(bb);
        Iterator<ISSABasicBlock> iter = cfg.getPredNodes(bb);
        while (iter.hasNext()) {
            ISSABasicBlock pred = iter.next();
            if (pred.isEntryBlock()) {
                continue;
            }
            SSAInstruction lastInst = pred.getLastInstruction();
            if ((lastInst instanceof SSAConditionalBranchInstruction) || (lastInst instanceof SSASwitchInstruction)) {
                storedBBs.add(pred);
            } else {
                worklist.add(pred);
            }
        }
        backwardObtainBBsInLine(cfg, worklist, storedBBs);
    }

    private static List<TypingNode> potentialGStringNode(SymbolTable symTable, SSAAbstractInvokeInstruction inst,
                                                         TypingSubGraph sg) {
        List<TypingNode> nodes = null;
        String sig = WalaUtil.getSignature(inst);
        String loc = TypingGraphConfig.getPotentialGStringLoc(sig);
        if (loc != null) {
            String[] indices = loc.split(TypingGraphConfig.SEPERATOR);
            for (String idx : indices) {
                int lc = Integer.parseInt(idx);
                int useVal = inst.getUse(lc);
                if (symTable.isConstant(useVal)) {
                    TypingNode node = null;
                    if (!inst.isStatic()) {
                        lc -= 1;
                    }
                    TypeReference type = inst.getDeclaredTarget().getParameterType(lc);
                    if (type.isPrimitiveType()) {
                        // TODO
                    } else if (symTable.isStringConstant(useVal)) {
                        node = sg.findOrCreate(useVal);
                    }
                    if (node != null) {
                        if (nodes == null) {
                            nodes = new LinkedList<TypingNode>();
                        }
                        nodes.add(node);
                    }
                }
            }
        }

        return nodes;
    }

    // remove support for normal definition stmt; only support branch stmt.
    private static void typingGlobalStringInBB(SSACFG cfg, SymbolTable symTable, ISSABasicBlock bb,
                                               List<TypingNode> potentialGNodes, TypingSubGraph sg,
                                               SSAAbstractInvokeInstruction invoke, boolean forward) {
        if (forward) {
            for (int i = bb.getFirstInstructionIndex(); i <= bb.getLastInstructionIndex(); i++) {
                SSAInstruction inst = cfg.getInstructions()[i];
                if (inst == null || inst instanceof SSAConditionalBranchInstruction ||
                        inst instanceof SSASwitchInstruction) {
                    break;
                }
                // System.err.println("[" + i + "]" + inst);
                // typingGlobalStringInInstruction(symTable, inst,
                // potentialGNodes, sg);
            }
        } else {
            for (int i = bb.getLastInstructionIndex(); i >= bb.getFirstInstructionIndex(); i--) {
                SSAInstruction inst = cfg.getInstructions()[i];
                if (inst == null) {
                    break;
                } else if (inst instanceof SSAConditionalBranchInstruction || inst instanceof SSASwitchInstruction) {
                    typingGlobalStringInInstruction(symTable, inst, potentialGNodes, sg, invoke);
                    break;
                }
                // typingGlobalStringInInstruction(symTable, inst,
                // potentialGNodes, sg);
            }
        }
    }

    private static void typingGlobalStringInInstruction(SymbolTable symTable, SSAInstruction inst,
                                                        List<TypingNode> potentialGNodes, TypingSubGraph sg,
                                                        SSAAbstractInvokeInstruction invoke) {
        int val = -1, extraVal = -1;
        if (inst instanceof SSAPutInstruction ssaPut) {
            val = ssaPut.getVal();
        } else if (inst instanceof SSAArrayStoreInstruction ssaArrayStore) {
            val = ssaArrayStore.getValue();
        } else if (inst instanceof SSAConditionalBranchInstruction ssaConditionalBranch) {
            val = ssaConditionalBranch.getUse(0);
            extraVal = ssaConditionalBranch.getUse(1);
        } else if (inst instanceof SSASwitchInstruction ssaSwitch) {
            val = ssaSwitch.getUse(0);
        } else if (inst.hasDef()) {
            val = inst.getDef();
        }
        TypingNode valNode, extraNode;
        typingGlobalStringInInstruction_processNode(symTable, potentialGNodes, sg, invoke, val);
        typingGlobalStringInInstruction_processNode(symTable, potentialGNodes, sg, invoke, extraVal);
    }

    private static void typingGlobalStringInInstruction_processNode(SymbolTable symTable,
                                                                    List<TypingNode> potentialGNodes, TypingSubGraph sg,
                                                                    SSAAbstractInvokeInstruction invoke, int val) {
        TypingNode valNode;
        if (val != -1 && !symTable.isConstant(val)) {
            valNode = sg.find(val);
            if (valNode != null) {
                TypingRecord valRec = currentTypingGraph.findOrCreateTypingRecord(valNode.getGraphNodeId());
                for (TypingNode n : potentialGNodes) {
                    // currentTypingGraph.mergeClass(n, valNode);
                    TypingRecord rec = currentTypingGraph.findOrCreateTypingRecord(n.getGraphNodeId());
                    TypingConstraint c =
                            new TypingConstraint(valNode.getGraphNodeId(), TypingConstraint.GE, n.getGraphNodeId());
                    // a simply way to get the corresponding statement of the
                    // alert inst to act as the path element (I think it usually
                    // fails)
                    Set<TypingConstraint> fc = rec.getForwardTypingConstraints();
                    for (TypingConstraint tc : fc) {
                        List<Statement> path = tc.getPath();
                        boolean found = false;
                        for (Statement p : path) {
                            if (p.getKind() == Kind.PARAM_CALLER) {
                                ParamCaller pcaller = (ParamCaller) p;
                                if (pcaller.getInstruction().equals(invoke)) {
                                    c.addPath(pcaller);
                                    found = true;
                                    break;
                                }
                            } else if (p.getKind() == Kind.NORMAL_RET_CALLER) {
                                NormalReturnCaller nrc = (NormalReturnCaller) p;
                                if (nrc.getInstruction().equals(invoke)) {
                                    c.addPath(nrc);
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (found) {
                            break;
                        }
                    }
                    rec.addForwardTypingConstraint(c);
                    // valRec.addBackwardTypingConstraint(c);
                }
            }
        }
    }

}
