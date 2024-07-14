package edu.purdue.cs.toydroid.bidtext.graph;

public record SimpleGraphNode(int nodeId) {
    public static SimpleGraphNode make(int nodeId) {
        return new SimpleGraphNode(nodeId);
    }

    @Override
    public String toString() {
        return "[ID: " + nodeId + "]";
    }
}
