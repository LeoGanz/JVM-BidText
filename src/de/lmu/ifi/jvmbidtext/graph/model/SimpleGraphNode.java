package de.lmu.ifi.jvmbidtext.graph.model;

public record SimpleGraphNode(int nodeId) {
    public static SimpleGraphNode make(int nodeId) {
        return new SimpleGraphNode(nodeId);
    }

    @Override
    public String toString() {
        return "[ID: " + nodeId + "]";
    }
}
