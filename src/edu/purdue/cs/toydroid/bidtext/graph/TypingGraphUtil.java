package edu.purdue.cs.toydroid.bidtext.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
import com.ibm.wala.ipa.slicer.HeapStatement;
import com.ibm.wala.ipa.slicer.NormalReturnCaller;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.ParamCallee;
import com.ibm.wala.ipa.slicer.ParamCaller;
import com.ibm.wala.ipa.slicer.PhiStatement;
import com.ibm.wala.ipa.slicer.SDG;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.Predicate;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphSlicer;
import com.ibm.wala.viz.DotUtil;

import edu.purdue.cs.toydroid.bidtext.TextLeak;
import edu.purdue.cs.toydroid.bidtext.analysis.AnalysisUtil;
import edu.purdue.cs.toydroid.bidtext.analysis.SpecialModel;
import edu.purdue.cs.toydroid.utils.SimpleCounter;
import edu.purdue.cs.toydroid.utils.WalaUtil;

public class TypingGraphUtil {
	private static Logger logger = LogManager.getLogger(TypingGraphUtil.class);

	private static final Pattern NameValuePattern = Pattern.compile("((\\w|-|\\s)+[=:]\\+\\*\\^\\d+\\^\\*\\+)");

	public static Map<Entrypoint, TypingGraph> entry2Graph;
	private static TypingGraph currentTypingGraph;
	private static Map<SSAGetInstruction, TypingNode> ssaGet2Nodes;
	private static Map<PointerKey, TypingNode> sFieldHeaps;
	private static ClassHierarchy cha;

	static {
		entry2Graph = new HashMap<Entrypoint, TypingGraph>();
		ssaGet2Nodes = new HashMap<SSAGetInstruction, TypingNode>();
		sFieldHeaps = new HashMap<PointerKey, TypingNode>();
	}

	private static void find(Graph<Statement> sdg, Statement stmt,
			Set<Statement> left) {
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

	private static Graph<Statement> pruneSDG(final Graph<Statement> sdg,
			final Set<Statement> left) {
		return GraphSlicer.prune(sdg, new Predicate<Statement>() {

			@Override
			public boolean test(Statement t) {
				if (left.contains(t))
					return true;
				return false;
			}
		});
	}

	public static void buildTypingGraph(Entrypoint ep, CallGraph cg,
			Graph<Statement> sdg, ClassHierarchy _cha) {
		cha = _cha;
		TypingGraph graph = new TypingGraph(ep);
		entry2Graph.put(ep, graph);
		currentTypingGraph = graph;
		logger.info("   - Visit SDG ");
		Map<Statement, SimpleCounter> visited = new HashMap<Statement, SimpleCounter>();
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
		visited = null;
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
		propagate();
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

	private static void buildTypingGraphForStmt(CallGraph cg,
			Graph<Statement> sdg, Statement stmt,
			Map<Statement, SimpleCounter> visited) {
		// only scan top level stmt
		if (0 == sdg.getPredNodeCount(stmt)
				&& !(stmt.getKind() == Kind.HEAP_PARAM_CALLEE && stmt.getNode()
						.equals(cg.getFakeRootNode()))) {
			List<Object> worklist = new LinkedList<Object>();
			worklist.add(stmt);
			while (!worklist.isEmpty()) {
				Object obj = worklist.remove(0);
				Statement nextStmt;
				TypingNode cachedNode = null;
				if (obj instanceof TypingNode) {
					cachedNode = (TypingNode) obj;
					nextStmt = (Statement) worklist.remove(0);
				} else {
					nextStmt = (Statement) obj;
				}
				// logger.debug("      * {}", nextStmt.toString());
				buildTypingGraphForStmtBFS(cg, sdg, nextStmt, visited,
						cachedNode, worklist);
			}
			// buildTypingGraphForStmtDFS(cg, sdg, stmt, visited, null);
		}
	}

	private static void buildTypingGraphForStmtBFS(CallGraph cg,
			Graph<Statement> sdg, Statement stmt,
			Map<Statement, SimpleCounter> visited, TypingNode cachedNode,
			List<Object> worklist) {
		Kind kind = stmt.getKind();
		TypingNode newCachedNode = null;
		switch (kind) {
			case PHI:
				handlePhi(cg, sdg, stmt);
				break;
			case NORMAL:
				newCachedNode = handleNormal(cg, sdg, stmt, cachedNode);
				break;
			case PARAM_CALLER:
				newCachedNode = handleParamCaller(cg, sdg, stmt);
				break;
			case PARAM_CALLEE:
				handleParamCallee(cg, sdg, stmt, cachedNode);
				break;
			case NORMAL_RET_CALLER:
				handleNormalRetCaller(cg, sdg, stmt, cachedNode);
				break;
			case NORMAL_RET_CALLEE:
				newCachedNode = cachedNode;
				// System.err.println("NRCallee: " + cachedNode);
				break;
			case HEAP_RET_CALLEE:
				handleHeapRetCallee(cg, sdg, stmt, cachedNode);
				break;
			case HEAP_RET_CALLER:
				newCachedNode = handleHeapRetCaller(cg, sdg, stmt);
				break;
			case HEAP_PARAM_CALLEE:
				newCachedNode = handleHeapParamCallee(cg, sdg, stmt);
				break;
			case HEAP_PARAM_CALLER:
			default:
				break;
		}
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

	@Deprecated
	private static void buildTypingGraphForStmtDFS(CallGraph cg,
			Graph<Statement> sdg, Statement stmt,
			Map<Statement, SimpleCounter> visited, TypingNode cachedNode) {
		Kind kind = stmt.getKind();
		TypingNode newCachedNode = null;
		switch (kind) {
			case PHI:
				handlePhi(cg, sdg, stmt);
				break;
			case NORMAL:
				newCachedNode = handleNormal(cg, sdg, stmt, cachedNode);
				break;
			case PARAM_CALLER:
				newCachedNode = handleParamCaller(cg, sdg, stmt);
				break;
			case PARAM_CALLEE:
				handleParamCallee(cg, sdg, stmt, cachedNode);
				break;
			case NORMAL_RET_CALLER:
				handleNormalRetCaller(cg, sdg, stmt, cachedNode);
				break;
			case NORMAL_RET_CALLEE:
				newCachedNode = cachedNode;
				// System.err.println("NRCallee: " + cachedNode);
				break;
			case HEAP_RET_CALLEE:
				handleHeapRetCallee(cg, sdg, stmt, cachedNode);
				break;
			case HEAP_RET_CALLER:
				newCachedNode = handleHeapRetCaller(cg, sdg, stmt);
				break;
			case HEAP_PARAM_CALLEE:
				newCachedNode = handleHeapParamCallee(cg, sdg, stmt);
				break;
			case HEAP_PARAM_CALLER:
			default:
				break;
		}
		// if (!visited.contains(stmt)) {
		if (!statementVisited(sdg, stmt, visited)) {
			// visited.add(stmt);
			if (stmt.getKind() == Statement.Kind.NORMAL) {
				NormalStatement ns = (NormalStatement) stmt;
				if ("arraystore 47[51] = 49".equals(ns.getInstruction()
						.toString())) {
					logger.debug("     arraystore 47[51] = 49 ::: {}",
							sdg.getPredNodeCount(stmt));
					Iterator<Statement> i = sdg.getPredNodes(stmt);
					while (i.hasNext()) {
						logger.debug("   {}", i.next().toString());
					}
				}
			}
			Iterator<Statement> iter = sdg.getSuccNodes(stmt);
			while (iter.hasNext()) {
				stmt = iter.next();
				buildTypingGraphForStmtDFS(cg, sdg, stmt, visited,
						newCachedNode);
			}
		}
	}

	/**
	 * Return True if stmt has more than 1 incoming edges and all these edges
	 * have been traversed.
	 * 
	 * @param sdg
	 * @param stmt
	 * @param visited
	 * @return
	 */
	private static boolean statementVisited(Graph<Statement> sdg,
			Statement stmt, Map<Statement, SimpleCounter> visited) {
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
				newCounter = null;
			}
		}

		return v;
	}

	/************************************************************/
	/************** Handle Specific WALA Statement **************/
	/************************************************************/
	private static Statement cachedStmt = null;

	private static void handlePhi(CallGraph cg, Graph<Statement> sdg,
			Statement stmt) {
		CGNode cgNode = stmt.getNode();
		if (cgNode.getMethod().isSynthetic()) {
			return;
		}
		TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
		PhiStatement phiStmt = (PhiStatement) stmt;
		handleSSAPhi(cgNode, phiStmt.getPhi(), sg);
	}

	private static TypingNode handleNormal(CallGraph cg, Graph<Statement> sdg,
			Statement stmt, TypingNode cachedNode) {
		CGNode cgNode = stmt.getNode();
		TypingNode newCachedNode = null;
		if (cgNode.getMethod().isSynthetic()) {
			return newCachedNode;
		}
		TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
		NormalStatement nstmt = (NormalStatement) stmt;
		SSAInstruction inst = nstmt.getInstruction();

		if (inst instanceof SSAPutInstruction) {
			newCachedNode = handleSSAPut(cgNode, nstmt,
					(SSAPutInstruction) inst, sg);
			// System.err.println("SSAPut: " + inst + " \n\t [" + newCachedNode
			// + "]");
		} else if (inst instanceof SSAGetInstruction) {
			// System.err.println("SSAGet: " + inst + " \n\t [" + cachedNode +
			// "]");
			handleSSAGet(cgNode, nstmt, (SSAGetInstruction) inst, sg,
					cachedNode);
		} else if (inst instanceof SSACheckCastInstruction) {
			handleSSACheckCast(cgNode, nstmt, (SSACheckCastInstruction) inst,
					sg);
		} else if (inst instanceof SSANewInstruction) {
			handleSSANew(cgNode, nstmt, (SSANewInstruction) inst, sg);
		} else if (inst instanceof SSAArrayLoadInstruction) {
			handleSSAArrayLoad(cgNode, nstmt, (SSAArrayLoadInstruction) inst,
					sg);
		} else if (inst instanceof SSAArrayStoreInstruction) {
			handleSSAArrayStore(cgNode, nstmt, (SSAArrayStoreInstruction) inst,
					sg);
		} else if (inst instanceof SSAReturnInstruction) {
			newCachedNode = handleSSAReturn(cgNode, nstmt,
					(SSAReturnInstruction) inst, sg);
		} else if (inst instanceof SSAInstanceofInstruction) {
			handleSSAInstanceof(cgNode, nstmt, (SSAInstanceofInstruction) inst,
					sg);
		} else if (inst instanceof SSABinaryOpInstruction) {
			handleSSABinaryOp(cgNode, nstmt, (SSABinaryOpInstruction) inst, sg);
		} else if (!(inst instanceof SSAAbstractInvokeInstruction)) {
			// System.err.println("Unrecognized Normal Stmt: " + stmt);
		} // invoke is ignored.
		return newCachedNode;
	}

	private static TypingNode handleParamCaller(CallGraph cg,
			Graph<Statement> sdg, Statement stmt) {
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
				AnalysisUtil.associateLayout2Activity(inst, cgNode);
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

	private static void handleParamCallee(CallGraph cg, Graph<Statement> sdg,
			Statement stmt, TypingNode cachedNode) {
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
			TypingConstraint c = new TypingConstraint(
					paramNode.getGraphNodeId(), TypingConstraint.EQ,
					cachedNode.getGraphNodeId());
			if (cachedStmt != null)
				c.addPath(cachedStmt);
			c.addPath(stmt);
			orec.addForwardTypingConstraint(c);
			nrec.addBackwardTypingConstraint(c);
			cachedStmt = null;
		}
	}

	private static void handleNormalRetCaller(CallGraph cg,
			Graph<Statement> sdg, Statement stmt, TypingNode cachedNode) {
		NormalReturnCaller nrc = (NormalReturnCaller) stmt;
		CGNode cgNode = nrc.getNode();
		TypingSubGraph sg = currentTypingGraph.findOrCreateSubGraph(cgNode);
		if (sdg.getPredNodeCount(stmt) == 0) {// API call?
			handleSSAInvokeAPI(cgNode, stmt, nrc.getInstruction(), sg);
		} else if (nrc.getInstruction().hasDef()) {
			if (cachedNode == null) {
				System.err.println("No Return Object is found for NormalRetCaller: "
						+ nrc);
			} else {
				int ret = nrc.getValueNumber();
				TypingNode retNode = sg.findOrCreate(ret);
				// currentTypingGraph.mergeClass(cachedNode, retNode);
				TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(cachedNode.getGraphNodeId());
				TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(retNode.getGraphNodeId());
				TypingConstraint c = new TypingConstraint(
						retNode.getGraphNodeId(), TypingConstraint.EQ,
						cachedNode.getGraphNodeId());
				if (cachedStmt != null)
					c.addPath(cachedStmt);
				c.addPath(stmt);
				orec.addForwardTypingConstraint(c);
				nrec.addBackwardTypingConstraint(c);
				cachedStmt = null;
			}
		}
	}

	private static void handleHeapRetCallee(CallGraph cg, Graph<Statement> sdg,
			Statement stmt, TypingNode cachedNode) {
		if (cachedNode == null || !cachedNode.isStaticField()
				|| stmt.getNode().equals(cg.getFakeRootNode())) {
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

	private static TypingNode handleHeapRetCaller(CallGraph cg,
			Graph<Statement> sdg, Statement stmt) {
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

	private static TypingNode handleHeapParamCallee(CallGraph cg,
			Graph<Statement> sdg, Statement stmt) {
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
	private static TypingNode handleSSAPut(CGNode cgNode, NormalStatement stmt,
			SSAPutInstruction inst, TypingSubGraph sg) {
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
		TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(valNode.getGraphNodeId());
		TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(refNode.getGraphNodeId());
		TypingConstraint c = new TypingConstraint(refNode.getGraphNodeId(),
				TypingConstraint.EQ, valNode.getGraphNodeId());
		c.addPath(stmt);
		orec.addForwardTypingConstraint(c);
		nrec.addBackwardTypingConstraint(c);

		currentTypingGraph.collectOutgoingField(refNode);
		return refNode;
	}

	private static void handleSSAGet(CGNode cgNode, NormalStatement stmt,
			SSAGetInstruction inst, TypingSubGraph sg, TypingNode cachedNode) {
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
					currentTypingGraph.setTypingRecord(
							cachedNode.getGraphNodeId(), rec);
				} else {
					TypingConstraint c = new TypingConstraint(
							prevNode.getGraphNodeId(), TypingConstraint.EQ,
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
				TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(cachedNode.getGraphNodeId());
				TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(defNode.getGraphNodeId());
				TypingConstraint c = new TypingConstraint(
						defNode.getGraphNodeId(), TypingConstraint.EQ,
						cachedNode.getGraphNodeId());
				c.addPath(stmt);
				orec.addForwardTypingConstraint(c);
				nrec.addForwardTypingConstraint(c);
			}
		} else {
			// some incoming field access from other entrypoint scope
			TypingNode refNode;
			if (inst.isStatic()) {
				refNode = sg.createStaticFieldNode(inst.getDeclaredField());
			} else {
				refNode = sg.createInstanceFieldNode(inst.getRef(),
						inst.getDeclaredField());
			}
			// currentTypingGraph.mergeClass(refNode, defNode);
			TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(refNode.getGraphNodeId());
			TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(defNode.getGraphNodeId());
			TypingConstraint c = new TypingConstraint(defNode.getGraphNodeId(),
					TypingConstraint.EQ, refNode.getGraphNodeId());
			c.addPath(stmt);
			orec.addForwardTypingConstraint(c);
			nrec.addForwardTypingConstraint(c);

			currentTypingGraph.setPossibleExternalInput(refNode.getGraphNodeId());
			ssaGet2Nodes.put(inst, refNode);
		}
	}

	private static void handleSSACheckCast(CGNode cgNode, NormalStatement stmt,
			SSACheckCastInstruction inst, TypingSubGraph sg) {
		int val = inst.getVal(); // rhs
		int ret = inst.getResult(); // lhs
		TypingNode valNode, retNode;
		valNode = sg.findOrCreate(val);
		retNode = sg.findOrCreate(ret);
		// currentTypingGraph.mergeClass(valNode, retNode);
		TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(valNode.getGraphNodeId());
		TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(retNode.getGraphNodeId());
		TypingConstraint c = new TypingConstraint(valNode.getGraphNodeId(),
				TypingConstraint.EQ, retNode.getGraphNodeId());
		c.addPath(stmt);
		orec.addForwardTypingConstraint(c);
		nrec.addForwardTypingConstraint(c);
	}

	private static void handleSSANew(CGNode cgNode, NormalStatement stmt,
			SSANewInstruction inst, TypingSubGraph sg) {
		int def = inst.getDef();
		TypingNode defNode = sg.findOrCreate(def);
		defNode.joke();
	}

	private static void handleSSAArrayLoad(CGNode cgNode, NormalStatement stmt,
			SSAArrayLoadInstruction inst, TypingSubGraph sg) {
		int ref = inst.getArrayRef(); // rhs
		int def = inst.getDef(); // lhs
		TypingNode refNode = sg.findOrCreate(ref);
		TypingNode defNode = sg.findOrCreate(def);
		// currentTypingGraph.mergeClass(refNode, defNode);
		TypingRecord orec = currentTypingGraph.findOrCreateTypingRecord(refNode.getGraphNodeId());
		TypingRecord nrec = currentTypingGraph.findOrCreateTypingRecord(defNode.getGraphNodeId());
		TypingConstraint c = new TypingConstraint(defNode.getGraphNodeId(),
				TypingConstraint.EQ, refNode.getGraphNodeId());
		c.addPath(stmt);
		orec.addForwardTypingConstraint(c);
		nrec.addForwardTypingConstraint(c);
	}

	private static void handleSSAArrayStore(CGNode cgNode,
			NormalStatement stmt, SSAArrayStoreInstruction inst,
			TypingSubGraph sg) {
		int ref = inst.getArrayRef(); // lhs
		int val = inst.getValue(); // rhs
		TypingNode refNode = sg.findOrCreate(ref);
		TypingNode valNode = sg.findOrCreate(val);
		// currentTypingGraph.mergeClass(valNode, refNode);
		TypingRecord rec = currentTypingGraph.getTypingRecord(valNode.getGraphNodeId());
		if (rec == null) {
			rec = currentTypingGraph.findOrCreateTypingRecord(refNode.getGraphNodeId());
			currentTypingGraph.setTypingRecord(valNode.getGraphNodeId(), rec);
		} else {
			currentTypingGraph.setTypingRecord(refNode.getGraphNodeId(), rec);
		}
	}

	private static TypingNode handleSSAReturn(CGNode cgNode,
			NormalStatement stmt, SSAReturnInstruction inst, TypingSubGraph sg) {
		TypingNode retNode = null;
		if (!inst.returnsVoid()) {
			int ret = inst.getResult();
			retNode = sg.findOrCreate(ret);
			cachedStmt = stmt;
		}
		return retNode;
	}

	private static void handleSSAInstanceof(CGNode cgNode,
			NormalStatement stmt, SSAInstanceofInstruction inst,
			TypingSubGraph sg) {
		int ref = inst.getRef();
		int def = inst.getDef();
		TypingNode refNode = sg.findOrCreate(ref);
		TypingNode defNode = sg.findOrCreate(def);
		// currentTypingGraph.mergeClass(refNode, defNode);
		// TODO: same as "a = b"?
	}

	private static void handleSSABinaryOp(CGNode cgNode, NormalStatement stmt,
			SSABinaryOpInstruction inst, TypingSubGraph sg) {
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
		TypingConstraint c0 = new TypingConstraint(defNode.getGraphNodeId(),
				TypingConstraint.GE_ASSIGN, use0Node.getGraphNodeId());
		TypingConstraint c1 = new TypingConstraint(defNode.getGraphNodeId(),
				TypingConstraint.GE_ASSIGN, use1Node.getGraphNodeId());
		defRec.addBackwardTypingConstraint(c0);
		defRec.addBackwardTypingConstraint(c1);
		use0Rec.addForwardTypingConstraint(c0);
		use1Rec.addForwardTypingConstraint(c1);
	}

	private static void handleSSAInvokeAPI(CGNode cgNode, Statement stmt,
			SSAAbstractInvokeInstruction inst, TypingSubGraph sg) {
		int apiType = AnalysisUtil.tryRecordInterestingNode(inst, sg, cha);

		int nVal, nFreeVar = 0, nConstVar = 0;
		int nParam = inst.getNumberOfParameters();
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
		if (apiSig.startsWith("java.lang.StringBuilder.append(")
				|| apiSig.startsWith("java.lang.StringBuilder.<init>(Ljava/")) {
			handleStringBuilderAppend(cgNode, defNode, defRec, thisNode,
					thisRec, sg.find(inst.getUse(1)));
			return;
		} else if (apiSig.startsWith("java.lang.StringBuilder.toString(")) {
			handleStringBuilderToString(defNode, defRec, thisNode, thisRec);
			return;
		}
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
				TypingConstraint c = new TypingConstraint(
						pNode.getGraphNodeId(), TypingConstraint.GE,
						cNode.getGraphNodeId());
				cRec.addForwardTypingConstraint(c);
			}
			if (apiType != 2) {
				if (thisRec != null) {
					TypingConstraint c = new TypingConstraint(
							thisNode.getGraphNodeId(), apiConstraint,
							pNode.getGraphNodeId());
					pRec.addForwardTypingConstraint(c);
					thisRec.addBackwardTypingConstraint(c);
				} else if (defRec != null) {
					TypingConstraint c = new TypingConstraint(
							defNode.getGraphNodeId(), apiConstraint,
							pNode.getGraphNodeId());
					pRec.addForwardTypingConstraint(c);
					defRec.addBackwardTypingConstraint(c);
				}
			}
		}
		if (nFreeVar == 0 && apiType != 2) {
			for (int cdx = 0; cdx < nConstVar; cdx++) {
				TypingNode cNode = constNodes[cdx];
				TypingRecord cRec = currentTypingGraph.findOrCreateTypingRecord(cNode.getGraphNodeId());
				if (thisRec != null) {
					TypingConstraint c = new TypingConstraint(
							thisNode.getGraphNodeId(), TypingConstraint.GE,
							cNode.getGraphNodeId());
					cRec.addForwardTypingConstraint(c);
				} else if (defRec != null) {
					TypingConstraint c = new TypingConstraint(
							defNode.getGraphNodeId(), TypingConstraint.GE,
							cNode.getGraphNodeId());
					cRec.addForwardTypingConstraint(c);
					// defRec.addBackwardTypingConstraint(c);
				}
			}
		}
		if (thisRec != null && defRec != null && apiType != 2) {
			TypingConstraint c = new TypingConstraint(defNode.getGraphNodeId(),
					apiConstraint, thisNode.getGraphNodeId());
			thisRec.addForwardTypingConstraint(c);
			// if (apiConstraint != TypingConstraint.GE_UNIDIR)
			defRec.addBackwardTypingConstraint(c);
		}
	}

	private static void handleStringBuilderAppend(CGNode cgNode,
			TypingNode defNode, TypingRecord defRec, TypingNode thisNode,
			TypingRecord thisRec, TypingNode paramNode) {
		TypingRecord paramRec = currentTypingGraph.findOrCreateTypingRecord(paramNode.getGraphNodeId());
		if (paramNode.isConstant()) {
			String str;
			if (paramNode.isString()) {
				str = cgNode.getIR()
						.getSymbolTable()
						.getStringValue(paramNode.value);
			} else {
				str = cgNode.getIR()
						.getSymbolTable()
						.getConstantValue(paramNode.value)
						.toString();
			}
			thisRec.addTypingAppend(str);
		} else {
			thisRec.addTypingAppend(paramNode.getGraphNodeId());
		}
		TypingConstraint c = new TypingConstraint(thisNode.getGraphNodeId(),
				TypingConstraint.GE_APPEND, paramNode.getGraphNodeId());
		paramRec.addForwardTypingConstraint(c);
		thisRec.addBackwardTypingConstraint(c);
		if (defRec != null) {
			defRec.addTypingAppend(thisRec);
			c = new TypingConstraint(defNode.getGraphNodeId(),
					TypingConstraint.GE_APPEND, paramNode.getGraphNodeId());
			paramRec.addForwardTypingConstraint(c);
			defRec.addBackwardTypingConstraint(c);
			c = new TypingConstraint(defNode.getGraphNodeId(),
					TypingConstraint.GE_APPEND, thisNode.getGraphNodeId());
			thisRec.addForwardTypingConstraint(c);
			defRec.addBackwardTypingConstraint(c);
		}
	}

	private static void handleStringBuilderToString(TypingNode defNode,
			TypingRecord defRec, TypingNode thisNode, TypingRecord thisRec) {
		if (defRec != null) {
			TypingConstraint c = new TypingConstraint(defNode.getGraphNodeId(),
					TypingConstraint.EQ, thisNode.getGraphNodeId());
			thisRec.addForwardTypingConstraint(c);
			defRec.addBackwardTypingConstraint(c);
		}

	}

	private static void handleSSAPhi(CGNode cgNode, SSAPhiInstruction inst,
			TypingSubGraph sg) {
		int def = inst.getDef();
		int nUse = inst.getNumberOfUses();
		TypingNode defNode = sg.findOrCreate(def);
		// logger.info("PHI: {}", inst.toString());
		TypingRecord defRec = sg.typingGraph.findOrCreateTypingRecord(defNode.getGraphNodeId());
		for (int i = 0; i < nUse; i++) {
			// logger.info(" i = {}:{}", i, inst.getUse(i));
			int valueNumber = inst.getUse(i);
			if (valueNumber == AbstractIntStackMachine.TOP) {
				continue;
			}
			TypingNode useNode = sg.findOrCreate(valueNumber);
			TypingRecord useRec = sg.typingGraph.findOrCreateTypingRecord(useNode.getGraphNodeId());
			TypingConstraint c = new TypingConstraint(defNode.getGraphNodeId(),
					TypingConstraint.GE, useNode.getGraphNodeId());
			defRec.addBackwardTypingConstraint(c);
			useRec.addForwardTypingConstraint(c);
			// currentTypingGraph.mergeClass(useNode, defNode);
		}
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

	private static void revisitInvokeInstruction(SSACFG cfg,
			SymbolTable symTable, SSAAbstractInvokeInstruction inst,
			TypingSubGraph sg) {
		List<TypingNode> potentialGNodes = potentialGStringNode(symTable, inst,
				sg);
		if (potentialGNodes != null) {
			SSACFG.BasicBlock locatedBB = cfg.getBlockForInstruction(inst.iindex);
			List<ISSABasicBlock> worklist = new LinkedList<ISSABasicBlock>();
			Set<ISSABasicBlock> storedBBs = new HashSet<ISSABasicBlock>();
			worklist.add(locatedBB);
			forwardObtainBBsInLine(cfg, worklist, storedBBs);
			for (ISSABasicBlock bb : storedBBs) {
				typingGlobalStringInBB(cfg, symTable, bb, potentialGNodes, sg,
						true);
			}
			worklist.add(locatedBB);
			storedBBs.clear();
			backwardObtainBBsInLine(cfg, worklist, storedBBs);
			storedBBs.remove(locatedBB);
			for (ISSABasicBlock bb : storedBBs) {
				typingGlobalStringInBB(cfg, symTable, bb, potentialGNodes, sg,
						false);
			}
			worklist = null;
			storedBBs = null;
		}
	}

	private static void forwardObtainBBsInLine(SSACFG cfg,
			List<ISSABasicBlock> worklist, Set<ISSABasicBlock> storedBBs) {
		ISSABasicBlock bb;
		if (worklist.isEmpty()) {
			return;
		}
		bb = worklist.remove(0);
		if (bb.isExitBlock() || cfg.getPredNodeCount(bb) > 1) {
			return;
		}
		storedBBs.add(bb);
		SSAInstruction lastInst = bb.getLastInstruction();
		if (!(lastInst instanceof SSAConditionalBranchInstruction)
				&& !(lastInst instanceof SSASwitchInstruction)) {
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

	private static void backwardObtainBBsInLine(SSACFG cfg,
			List<ISSABasicBlock> worklist, Set<ISSABasicBlock> storedBBs) {
		ISSABasicBlock bb;
		if (worklist.isEmpty()) {
			return;
		}
		bb = worklist.remove(0);
		if (bb.isEntryBlock() || cfg.getPredNodeCount(bb) > 1) {
			return;
		}
		storedBBs.add(bb);
		Iterator<ISSABasicBlock> iter = cfg.getPredNodes(bb);
		while (iter.hasNext()) {
			ISSABasicBlock pred = iter.next();
			if (pred.isEntryBlock())
				continue;
			SSAInstruction lastInst = pred.getLastInstruction();
			if ((lastInst instanceof SSAConditionalBranchInstruction)
					|| (lastInst instanceof SSASwitchInstruction)) {
				storedBBs.add(pred);
			} else {
				worklist.add(pred);
			}
		}
		backwardObtainBBsInLine(cfg, worklist, storedBBs);
	}

	private static List<TypingNode> potentialGStringNode(SymbolTable symTable,
			SSAAbstractInvokeInstruction inst, TypingSubGraph sg) {
		List<TypingNode> nodes = null;
		String sig = WalaUtil.getSignature(inst);
		String loc = TypingGraphConfig.getPotentialGStringLoc(sig);
		if (loc != null) {
			String[] indics = loc.split(TypingGraphConfig.SEPERATOR);
			for (String idx : indics) {
				int lc = Integer.parseInt(idx);
				int useVal = inst.getUse(lc);
				if (symTable.isConstant(useVal)) {
					TypingNode node = null;
					if (!inst.isStatic()) {
						lc -= 1;
					}
					TypeReference type = inst.getDeclaredTarget()
							.getParameterType(lc);
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
	private static void typingGlobalStringInBB(SSACFG cfg,
			SymbolTable symTable, ISSABasicBlock bb,
			List<TypingNode> potentialGNodes, TypingSubGraph sg, boolean forward) {
		if (forward) {
			for (int i = bb.getFirstInstructionIndex(); i <= bb.getLastInstructionIndex(); i++) {
				SSAInstruction inst = cfg.getInstructions()[i];
				if (inst == null
						|| inst instanceof SSAConditionalBranchInstruction
						|| inst instanceof SSASwitchInstruction) {
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
				} else if (inst instanceof SSAConditionalBranchInstruction
						|| inst instanceof SSASwitchInstruction) {
					typingGlobalStringInInstruction(symTable, inst,
							potentialGNodes, sg);
					break;
				}
				// typingGlobalStringInInstruction(symTable, inst,
				// potentialGNodes, sg);
			}
		}
	}

	private static void typingGlobalStringInInstruction(SymbolTable symTable,
			SSAInstruction inst, List<TypingNode> potentialGNodes,
			TypingSubGraph sg) {
		int val = -1, extraVal = -1;
		if (inst instanceof SSAPutInstruction) {
			val = ((SSAPutInstruction) inst).getVal();
		} else if (inst instanceof SSAArrayStoreInstruction) {
			val = ((SSAArrayStoreInstruction) inst).getValue();
		} else if (inst instanceof SSAConditionalBranchInstruction) {
			SSAConditionalBranchInstruction cond = (SSAConditionalBranchInstruction) inst;
			val = cond.getUse(0);
			extraVal = cond.getUse(1);
		} else if (inst instanceof SSASwitchInstruction) {
			val = ((SSASwitchInstruction) inst).getUse(0);
		} else if (inst.hasDef()) {
			val = inst.getDef();
		}
		TypingNode valNode = null, extraNode = null;
		if (val != -1 && !symTable.isConstant(val)) {
			valNode = sg.find(val);
			if (valNode != null) {
				TypingRecord valRec = currentTypingGraph.findOrCreateTypingRecord(valNode.getGraphNodeId());
				for (TypingNode n : potentialGNodes) {
					// currentTypingGraph.mergeClass(n, valNode);
					TypingRecord rec = currentTypingGraph.findOrCreateTypingRecord(n.getGraphNodeId());
					TypingConstraint c = new TypingConstraint(
							valNode.getGraphNodeId(), TypingConstraint.GE,
							n.getGraphNodeId());
					rec.addForwardTypingConstraint(c);
					// valRec.addBackwardTypingConstraint(c);
				}
			}
		}
		if (extraVal != -1 && !symTable.isConstant(extraVal)) {
			extraNode = sg.find(extraVal);
			if (extraNode != null) {
				TypingRecord extraRec = currentTypingGraph.findOrCreateTypingRecord(extraNode.getGraphNodeId());
				for (TypingNode n : potentialGNodes) {
					// currentTypingGraph.mergeClass(n, extraNode);
					TypingRecord rec = currentTypingGraph.findOrCreateTypingRecord(n.getGraphNodeId());
					TypingConstraint c = new TypingConstraint(
							extraNode.getGraphNodeId(), TypingConstraint.GE,
							n.getGraphNodeId());
					rec.addForwardTypingConstraint(c);
					// extraRec.addBackwardTypingConstraint(c);
				}
			}
		}
	}

	/************************************************************/
	/******************** Typing Propagation ********************/
	/************************************************************/
	private static void propagate() {
		List<TypingRecord> worklist = new LinkedList<TypingRecord>();
		// PASS 1: forward
		initWorklistPassOne(worklist);
		while (!worklist.isEmpty()) {
			TypingRecord rec = worklist.remove(0);
			propagateOneRecordForward(rec, worklist);
		}
		while (true) {
			boolean changed = false;
			// PASS 2: backward
			initWorklistPassTwo(worklist);
			while (!worklist.isEmpty()) {
				TypingRecord rec = worklist.remove(0);
				changed = changed | propagateOneRecordBackward(rec, worklist);
			}
			if (!changed) {
				break;
			}
			// PASS 3: forward
			initWorklistPassThree(worklist);
			while (!worklist.isEmpty()) {
				TypingRecord rec = worklist.remove(0);
				changed = changed | propagateOneRecordForward(rec, worklist);
			}
			if (!changed) {
				break;
			}
		}
	}

	private static void initWorklistPassOne(List<TypingRecord> worklist) {
		Iterator<TypingNode> iter = currentTypingGraph.iterateNodes();
		while (iter.hasNext()) {
			TypingNode tn = iter.next();
			TypingRecord rec = currentTypingGraph.getTypingRecord(tn.getGraphNodeId());

			if (rec == null) {
				continue;
			}
			if (tn.isConstant()) {
				// if (rec == null) {
				// rec =
				// currentTypingGraph.findOrCreateTypingRecord(tn.getGraphNodeId());
				// }
				if (tn.isString()) {
					String text = tn.cgNode.getIR()
							.getSymbolTable()
							.getStringValue(tn.value);
					if (text != null) {
						text = text.trim();
						if (!text.isEmpty()) {
							rec.addTypingText(text);
							worklist.add(rec);
						}
					}
				} else {
					Object o = tn.cgNode.getIR()
							.getSymbolTable()
							.getConstantValue(tn.value);
					rec.addTypingConstant(o);
					worklist.add(rec);
				}
			} else if (/* rec != null && */rec.hasExternalFields()) {
				worklist.add(rec);
			}
			Iterator<String> appendIter = rec.iteratorAppendResults();
			while (appendIter.hasNext()) {
				String str = appendIter.next();
				// System.err.println(tn.getGraphNodeId() + "  >> "+str);
				if (str.startsWith("http:") || str.startsWith("https:")) {
					str = str.substring(5);
				}
				Matcher matcher = NameValuePattern.matcher(str);
				while (matcher.find()) {
					// System.err.println(matcher.groupCount() + "  "
					// + matcher.group(1));
					String matched = matcher.group(1);
					int vStartIdx = matched.indexOf(TypingRecord.APPEND_VAR_PREFIX);
					String varStr = matched.substring(
							vStartIdx + TypingRecord.APPEND_VAR_PREFIX.length(),
							matched.length()
									- TypingRecord.APPEND_VAR_POSTFIX.length());
					int seperatorIdx = matched.indexOf('=');
					if (seperatorIdx <= 0) {
						seperatorIdx = matched.indexOf(':');
					}
					String name = matched.substring(0, seperatorIdx);
					int var = Integer.parseInt(varStr);
					TypingRecord r = currentTypingGraph.getTypingRecord(var);
					r.addTypingText(name);
				}
			}
		}
	}

	private static boolean propagateOneRecordForward(TypingRecord record,
			List<TypingRecord> worklist) {
		boolean changed = false;
		Set<TypingConstraint> constraints = record.getForwardTypingConstraints();
		for (TypingConstraint ct : constraints) {
			int nextId = ct.lhs;
			// TypingNode nextNode = currentTypingGraph.getNode(nextId);
			TypingRecord nextRec = currentTypingGraph.getTypingRecord(nextId);
			// System.err.println(record.getTypingTexts());
			if (nextRec.merge(record)) {
				worklist.add(nextRec);
				changed = true;
			}
		}
		return changed;
	}

	private static void initWorklistPassTwo(List<TypingRecord> worklist) {
		Iterator<Map.Entry<SimpleGraphNode, TypingRecord>> iter = currentTypingGraph.iterateRecords();
		while (iter.hasNext()) {
			Map.Entry<SimpleGraphNode, TypingRecord> entry = iter.next();
			TypingRecord record = entry.getValue();
			if (record.hasBackwardConstraints()
					&& (record.hasConstants() || record.hasExternalFields())) {
				worklist.add(record);
			}
		}
	}

	private static boolean propagateOneRecordBackward(TypingRecord record,
			List<TypingRecord> worklist) {
		boolean changed = false;
		Set<TypingConstraint> constraints = record.getBackwardTypingConstraints();
		int lhs_assign = -1;
		int[] rhs_assign = { -1, -1 };
		int idx = 0;
		for (TypingConstraint ct : constraints) {
			int nextId = ct.rhs;
			int sym = ct.sym;
			TypingRecord nextRec = currentTypingGraph.getTypingRecord(nextId);
			if (sym == TypingConstraint.EQ || sym == TypingConstraint.GE) {
				if (nextRec.merge(record)) {
					worklist.add(nextRec);
					changed = true;
				}
			} else if (sym == TypingConstraint.GE_ASSIGN) {
				if (lhs_assign == -1) {
					lhs_assign = ct.lhs;
					rhs_assign[idx++] = nextId;
				} else {
					if (lhs_assign != ct.lhs) {
						logger.error(
								"     ? Inconsistent LHS for assignment: {} - {}",
								lhs_assign, ct.lhs);
					} else if (idx >= 2) {
						logger.error(
								"     ? Wrong Assignment with at least {} RHS",
								(idx + 1));
					} else {
						rhs_assign[idx] = nextId;
					}
					idx++;
				}
			} else if (sym == TypingConstraint.GE_APPEND) {
				if (nextRec.mergeIfEmptyTexts(record)) {
					worklist.add(nextRec);
					changed = true;
				}
			}
			// else if (sym == TypingConstraint.GE_UNIDIR) {
			// System.err.println(ct.lhs + "  ->- " + nextId);
			// }
		}
		if (idx == 1) {
			// e.g. a = b + b
			TypingRecord nextRec = currentTypingGraph.getTypingRecord(rhs_assign[0]);
			if (nextRec.merge(record)) {
				worklist.add(nextRec);
				changed = true;
			}
		} else if (idx == 2 && rhs_assign[1] != -1) {
			TypingRecord rec0 = currentTypingGraph.getTypingRecord(rhs_assign[0]);
			TypingRecord rec1 = currentTypingGraph.getTypingRecord(rhs_assign[1]);
			Set<String> texts0 = rec0.getTypingTexts();
			Set<String> texts1 = rec1.getTypingTexts();
			Set<Object> const0 = rec0.getTypingConstants();
			Set<Object> const1 = rec1.getTypingConstants();
			Set<SimpleGraphNode> inputs0 = rec0.getInputFields();
			Set<SimpleGraphNode> inputs1 = rec1.getInputFields();
			Set<SimpleGraphNode> outputs0 = rec0.getOutputFields();
			Set<SimpleGraphNode> outputs1 = rec1.getOutputFields();
			boolean changed0 = false, changed1 = false;
			// propagate texts
			if (!record.getTypingTexts().isEmpty()) {
				if (texts0.isEmpty()) {
					texts0.addAll(record.getTypingTexts());
					if (texts1.isEmpty()) {
						texts1.addAll(texts0);
						changed0 = true;
						changed1 = true;
					} else {
						texts0.removeAll(texts1);
						changed0 = !texts0.isEmpty();
					}
				} else {
					if (texts1.isEmpty()) {
						texts1.addAll(record.getTypingTexts());
						texts1.removeAll(texts0);
						changed1 = !texts1.isEmpty();
					}
				}
			}
			// propagate constants
			if (!record.getTypingConstants().isEmpty()) {
				if (const0.isEmpty()) {
					const0.addAll(record.getTypingConstants());
					if (const1.isEmpty()) {
						const1.addAll(const0);
						changed0 = true;
						changed1 = true;
					} else {
						const0.removeAll(const1);
						changed0 = (changed0 | !const0.isEmpty());
					}
				} else {
					if (const1.isEmpty()) {
						const1.addAll(record.getTypingConstants());
						const1.removeAll(const0);
						changed1 = (changed1 | !const1.isEmpty());
					}
				}
			}
			// propagate inputs
			if (!record.getInputFields().isEmpty()) {
				if (inputs0.isEmpty()) {
					inputs0.addAll(record.getInputFields());
					if (inputs1.isEmpty()) {
						inputs1.addAll(inputs0);
						changed0 = true;
						changed1 = true;
					} else {
						inputs0.removeAll(inputs1);
						changed0 = (changed0 | !inputs0.isEmpty());
					}
				} else {
					if (inputs1.isEmpty()) {
						inputs1.addAll(record.getInputFields());
						inputs1.removeAll(inputs0);
						changed1 = (changed1 | !inputs1.isEmpty());
					}
				}
			}
			// propagate outputs
			if (!record.getOutputFields().isEmpty()) {
				if (outputs0.isEmpty()) {
					outputs0.addAll(record.getOutputFields());
					if (outputs1.isEmpty()) {
						outputs1.addAll(outputs0);
						changed0 = true;
						changed1 = true;
					} else {
						outputs0.removeAll(outputs1);
						changed0 = (changed0 | !outputs0.isEmpty());
					}
				} else {
					if (outputs1.isEmpty()) {
						outputs1.addAll(record.getOutputFields());
						outputs1.removeAll(outputs0);
						changed1 = (changed1 | !outputs1.isEmpty());
					}
				}
			}
			// propagate
			if (changed0) {
				worklist.add(rec0);
				changed = true;
			}
			if (changed1) {
				worklist.add(rec1);
				changed = true;
			}
		}
		return changed;
	}

	private static void initWorklistPassThree(List<TypingRecord> worklist) {
		Iterator<Map.Entry<SimpleGraphNode, TypingRecord>> iter = currentTypingGraph.iterateRecords();
		while (iter.hasNext()) {
			Map.Entry<SimpleGraphNode, TypingRecord> entry = iter.next();
			TypingRecord record = entry.getValue();
			if (record.hasForwardConstraints()
					&& (record.hasConstants() || record.hasExternalFields())) {
				worklist.add(record);
			}
		}
	}
}
