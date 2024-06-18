package edu.purdue.cs.toydroid.bidtext.graph.construction;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.slicer.NormalReturnCaller;
import com.ibm.wala.ipa.slicer.ParamCaller;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.*;
import com.ibm.wala.types.TypeReference;
import edu.purdue.cs.toydroid.bidtext.graph.*;
import edu.purdue.cs.toydroid.utils.WalaUtil;

import java.util.*;

public class GlobalConstantStringProcessor {

    private final TypingGraph graph;

    public GlobalConstantStringProcessor(TypingGraph typingGraph) {
        this.graph = typingGraph;
    }

    void revisitTypingGraph() {
        Iterator<CGNode> allCGNodes = graph.iterateAllCGNodes();
        while (allCGNodes.hasNext()) {
            CGNode cgNode = allCGNodes.next();
            TypingSubGraph sg = graph.findOrCreateSubGraph(cgNode);
            revisitPotentialGString(cgNode, sg);
        }
    }

    private void revisitPotentialGString(CGNode cgNode, TypingSubGraph sg) {
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

    private void revisitInvokeInstruction(SSACFG cfg, SymbolTable symTable, SSAAbstractInvokeInstruction inst,
                                          TypingSubGraph sg) {
        List<TypingNode> potentialGNodes = potentialGStringNode(symTable, inst, sg);
        if (potentialGNodes != null) {
            SSACFG.BasicBlock locatedBB = cfg.getBlockForInstruction(inst.iIndex());
            List<ISSABasicBlock> worklist = new LinkedList<>();
            Set<ISSABasicBlock> storedBBs = new HashSet<>();
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

    private void forwardObtainBBsInLine(SSACFG cfg, List<ISSABasicBlock> worklist,
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
            Iterator<ISSABasicBlock> succNodes = cfg.getSuccNodes(bb);
            while (succNodes.hasNext()) {
                ISSABasicBlock succ = succNodes.next();
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
        Iterator<ISSABasicBlock> predNodes = cfg.getPredNodes(bb);
        while (predNodes.hasNext()) {
            ISSABasicBlock pred = predNodes.next();
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

    private List<TypingNode> potentialGStringNode(SymbolTable symTable, SSAAbstractInvokeInstruction inst,
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
                            nodes = new LinkedList<>();
                        }
                        nodes.add(node);
                    }
                }
            }
        }

        return nodes;
    }

    // remove support for normal definition stmt; only support branch stmt.
    private void typingGlobalStringInBB(SSACFG cfg, SymbolTable symTable, ISSABasicBlock bb,
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

    private void typingGlobalStringInInstruction(SymbolTable symTable, SSAInstruction inst,
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
        typingGlobalStringInInstruction_processNode(symTable, potentialGNodes, sg, invoke, val);
        typingGlobalStringInInstruction_processNode(symTable, potentialGNodes, sg, invoke, extraVal);
    }

    private void typingGlobalStringInInstruction_processNode(SymbolTable symTable,
                                                             List<TypingNode> potentialGNodes, TypingSubGraph sg,
                                                             SSAAbstractInvokeInstruction invoke, int val) {
        TypingNode valNode;
        if (val != -1 && !symTable.isConstant(val)) {
            valNode = sg.find(val);
            if (valNode != null) {
                TypingRecord valRec = graph.findOrCreateTypingRecord(valNode.getGraphNodeId());
                for (TypingNode n : potentialGNodes) {
                    // currentTypingGraph.mergeClass(n, valNode);
                    TypingRecord rec = graph.findOrCreateTypingRecord(n.getGraphNodeId());
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
                            if (p.getKind() == Statement.Kind.PARAM_CALLER) {
                                ParamCaller pcaller = (ParamCaller) p;
                                if (pcaller.getInstruction().equals(invoke)) {
                                    c.addPath(pcaller);
                                    found = true;
                                    break;
                                }
                            } else if (p.getKind() == Statement.Kind.NORMAL_RET_CALLER) {
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
