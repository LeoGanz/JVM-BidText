package de.lmu.ifi.jvmbidtext.graph.model;

import com.ibm.wala.ipa.slicer.Statement;

import java.util.LinkedList;
import java.util.List;

public class TypingConstraint {
    public static final int EQ = 0x8;
    public static final int GE = 0xC;
    public static final int GE_ASSIGN = 0x10;
    public static final int GE_UNIDIR = 0x14;// uni-directional for certain APIs
    // (no backward propagation)
    public static final int GE_APPEND = 0x18;
    public static final int GE_PHI = 0x1c;// phi variables

    private int lhs;
    private int rhs;
    private int sym;

    private final List<Statement> path;// propagation path

    public TypingConstraint(int l, int s, int r) {
        lhs = l;
        sym = s;
        rhs = r;
        path = new LinkedList<>();
    }

    public boolean addPath(Statement stmt) {
        return path.add(stmt);
    }

    public List<Statement> getPath() {
        return path;
    }

    public boolean equals(Object o) {
        if (o instanceof TypingConstraint c) {
            return c.sym == sym && c.lhs == lhs && c.rhs == rhs;
        }
        return false;
    }

    public int hashCode() {
        return lhs * 65537 + rhs * 129 + sym;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(lhs);
        switch (sym) {
            case EQ:
                builder.append("=");
                break;
            case GE:
                builder.append(">=");
                break;
            case GE_APPEND:
                builder.append(">=[append]");
                break;
            case GE_UNIDIR:
                builder.append(">-");
                break;
            case GE_ASSIGN:
                builder.append(">=[assign]");
                break;
            default:
                builder.append("/\\");
                break;
        }
        builder.append(rhs);
        return builder.toString();
    }

    public int getLhs() {
        return lhs;
    }

    public void setLhs(int lhs) {
        this.lhs = lhs;
    }

    public int getRhs() {
        return rhs;
    }

    public void setRhs(int rhs) {
        this.rhs = rhs;
    }

    public int getSym() {
        return sym;
    }

    public void setSym(int sym) {
        this.sym = sym;
    }
}
