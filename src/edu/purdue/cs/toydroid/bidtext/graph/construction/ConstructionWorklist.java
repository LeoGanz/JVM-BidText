package edu.purdue.cs.toydroid.bidtext.graph.construction;

import com.ibm.wala.ipa.slicer.*;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import edu.purdue.cs.toydroid.bidtext.graph.TypingNode;

import java.util.*;

public class ConstructionWorklist implements Iterable<ConstructionWorklist.Item> {

    private final List<Item> delegate;

    // caches of statements to link the caller and callee both for method invocations and returns
    // callee (method reference) always means 'child method'
    private final Map<MethodReference, ParamCaller> calleesOfMethodInvocations;
    private final Map<MethodReference, NormalStatement> calleesOfReturns;

    // When getfield instructions are executed, a regular node for the local variable holding the result from getfield is created
    // Furthermore an artificial field node is created (vx999) to represent the field that is being accessed.
    // These artificial field nodes are stored in this map and can be used to link new artificial field nodes accessing the same field to all the other field accesses.
    private final Map<FieldReference, Set<TypingNode>> artificialFieldNodes;

    public ConstructionWorklist() {
        delegate = new LinkedList<>();
        calleesOfMethodInvocations = new HashMap<>();
        calleesOfReturns = new HashMap<>();
        artificialFieldNodes = new HashMap<>();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    public boolean add(Item item) {
        return delegate.add(item);
    }

    public boolean add(Statement statement) {
        return add(item(statement));
    }

    public boolean add(Statement statement, Optional<TypingNode> cachedNode) {
        return add(item(statement, cachedNode));
    }

    public Item removeFirst() {
        return delegate.removeFirst();
    }

    @Override
    public Iterator<Item> iterator() {
        return delegate.iterator();
    }

    public void cacheParamCaller(ParamCaller pcStmt) {
        if (pcStmt != null) {
            calleesOfMethodInvocations.put(pcStmt.getInstruction().getCallSite().getDeclaredTarget(), pcStmt);
        }
    }

    public void cacheCalleeOfReturn(NormalStatement normalStatement) {
        if (normalStatement != null) {
            calleesOfReturns.put(normalStatement.getNode().getMethod().getReference(), normalStatement);
        }
    }

    public void cacheArtificialFieldNode(FieldReference field, TypingNode node) {
        // TODO: only available while processing predecessors of a top level statement (each get a new worklist). Should this be maintained in TypingGraphUtil?
        artificialFieldNodes.computeIfAbsent(field, __ -> new HashSet<>()).add(node);
    }

    public Set<TypingNode> getArtificialFieldNodes(FieldReference field) {
        return artificialFieldNodes.computeIfAbsent(field, __ -> new HashSet<>());
    }

    public Item item(Statement stmt) {
        return new Item(stmt, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public Item item(Statement statement, Optional<TypingNode> cachedNode) {
        //TODO is cachedNode always statement.getNode()?
        if (statement instanceof ParamCallee paramCallee) {
            // the side that executes the call to a child method
            // Remove from cache as every param callee is preceded by a param caller which overwrites the cache anyway
            ParamCaller paramCaller =
                    calleesOfMethodInvocations.remove(paramCallee.getNode().getMethod().getReference());
            return new Item(statement, cachedNode, Optional.ofNullable(paramCaller), Optional.empty());
        } else if (statement instanceof NormalReturnCaller normalReturnCaller) {
            // the side that executes 'return x;'
            NormalStatement callee =
                    calleesOfReturns.remove(normalReturnCaller.getInstruction().getCallSite().getDeclaredTarget());
            return new Item(statement, cachedNode, Optional.empty(), Optional.ofNullable(callee));
        } else {
            return new Item(statement, cachedNode, Optional.empty(), Optional.empty());
        }
    }

    public static final class Item {
        private final Statement statement;
        private final Optional<TypingNode> cachedNode;
        private final Optional<ParamCaller> cachedParamCaller;
        private final Optional<NormalStatement> cachedNormalStatement;

        // Cannot use record because the constructor should be private to avoid mismatched object configuration where
        // e.g. cachedParamCaller is set for non-ParamCallee statements
        private Item(Statement statement, Optional<TypingNode> cachedNode, Optional<ParamCaller> cachedParamCaller,
                     Optional<NormalStatement> cachedNormalStatement) {
            this.statement = statement;
            this.cachedNode = cachedNode;
            this.cachedParamCaller = cachedParamCaller;
            this.cachedNormalStatement = cachedNormalStatement;
        }

        public Statement statement() {
            return statement;
        }

        public Optional<TypingNode> cachedNode() {
            return cachedNode;
        }

        public Optional<ParamCaller> cachedParamCaller() {
            return cachedParamCaller;
        }

        public Optional<NormalStatement> cachedNormalStatement() {
            return cachedNormalStatement;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || obj.getClass() != this.getClass()) {
                return false;
            }
            var that = (Item) obj;
            return Objects.equals(this.statement, that.statement) && Objects.equals(this.cachedNode, that.cachedNode) &&
                    Objects.equals(this.cachedParamCaller, that.cachedParamCaller) &&
                    Objects.equals(this.cachedNormalStatement, that.cachedNormalStatement);
        }

        @Override
        public int hashCode() {
            return Objects.hash(statement, cachedNode, cachedParamCaller, cachedNormalStatement);
        }

        @Override
        public String toString() {
            return "Item[" + "statement=" + statement + ", " + "cachedNode=" + cachedNode + ", " +
                    "cachedParamCaller=" + cachedParamCaller + ", " + "cachedNormalStatement=" + cachedNormalStatement +
                    ']';
        }

    }
}
