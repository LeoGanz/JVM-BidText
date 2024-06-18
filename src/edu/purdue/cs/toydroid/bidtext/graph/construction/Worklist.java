package edu.purdue.cs.toydroid.bidtext.graph.construction;

import com.ibm.wala.ipa.slicer.*;
import edu.purdue.cs.toydroid.bidtext.graph.TypingNode;

import java.util.*;

public class Worklist implements Iterable<Worklist.Item> {

    private final List<Item> delegate;
    private Optional<ParamCaller> latestParamCaller;
    private Optional<NormalStatement> latestNormalStatement;

    public Worklist() {
        delegate = new LinkedList<>();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    public boolean add(Item item) {
        return delegate.add(item);
    }

    public Item removeFirst() {
        return delegate.removeFirst();
    }

    @Override
    public Iterator<Item> iterator() {
        return delegate.iterator();
    }

    public void cacheLatestParamCaller(ParamCaller pcStmt) {
        latestParamCaller = pcStmt == null ? Optional.empty() : Optional.of(pcStmt);
    }

    public void cacheLatestNormalStatement(NormalStatement normalStatement) {
        latestNormalStatement = normalStatement == null ? Optional.empty() : Optional.of(normalStatement);
    }

    public Item item(Statement stmt) {
        return new Item(stmt, Optional.empty(), Optional.empty(), Optional.empty());
    }

    //TODO combine wiht add method
    public Item item(Statement statement, Optional<TypingNode> cachedNode) {
        if (statement instanceof ParamCallee paramCallee) {
//            System.out.println("binding param caller (1) to callee (2)");
//            System.out.println("(1): " + latestParamCaller.get());
//            System.out.println("(2): " + paramCallee);
            return new Item(statement, cachedNode, latestParamCaller, Optional.empty());
        } else if (statement instanceof NormalReturnCaller) {
            return new Item(statement, cachedNode, Optional.empty(), latestNormalStatement);
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
            return Objects.equals(this.statement, that.statement) &&
                    Objects.equals(this.cachedNode, that.cachedNode) &&
                    Objects.equals(this.cachedParamCaller, that.cachedParamCaller)
                    && Objects.equals(this.cachedNormalStatement, that.cachedNormalStatement);
        }

        @Override
        public int hashCode() {
            return Objects.hash(statement, cachedNode, cachedParamCaller, cachedNormalStatement);
        }

        @Override
        public String toString() {
            return "Item[" +
                    "statement=" + statement + ", " +
                    "cachedNode=" + cachedNode + ", " +
                    "cachedParamCaller=" + cachedParamCaller + ", " +
                    "cachedNormalStatement=" + cachedNormalStatement + ']';
        }

    }
}
