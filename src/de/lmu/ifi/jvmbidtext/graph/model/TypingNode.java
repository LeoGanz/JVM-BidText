package de.lmu.ifi.jvmbidtext.graph.model;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.util.graph.impl.NodeWithNumber;

public class TypingNode extends NodeWithNumber {
    public static final int SIMPLE = 0x0;
    public static final int STRING = 0x1;
    public static final int CONSTANT = 0x2;
    public static final int PARAM = 0x4;
    public static final int FIELD = 0x8;
    public static final int IFIELD = 0x10; // instance field
    public static final int SFIELD = 0x20; // static field
    public static final int FAKE_STRING = 0x40;
    public static final int FAKE_CONSTANT = 0x80;
    private boolean isSpecial = false;

    private int kind;
    private final CGNode cgNode; // the enclosing CGNode
    private final int value; // value number of variable. for fieldRef, a "fake"
    // value number is assigned for easy access in map
    private int obj; // obj value in instance field
    private FieldReference fieldRef;

    private TypingNode(CGNode node, int v, int k) {
        cgNode = node;
        value = v;
        kind = k;
    }

    public TypingNode(CGNode node, int v) {
        this(node, v, SIMPLE);
    }

    // static field
    public TypingNode(CGNode node, int v, FieldReference f) {
        this(node, v, FIELD | SFIELD);
        fieldRef = f;
    }

    // instance field
    public TypingNode(CGNode node, int fv, int v, FieldReference f) {
        this(node, fv, FIELD | IFIELD);
        obj = v;
        fieldRef = f;
    }

    public boolean isField() {
        return FIELD == (FIELD & kind);
    }

    public boolean isStaticField() {
        return SFIELD == (SFIELD & kind);
    }

    public boolean isInstanceField() {
        return IFIELD == (IFIELD & kind);
    }

    public boolean isConstant() {
        return (CONSTANT == (CONSTANT & kind));
    }

    public boolean isString() {
        return (STRING == (STRING & kind));
    }

    public boolean isFakeString() {
        return (FAKE_STRING == (FAKE_STRING & kind));
    }

    public boolean isFakeConstant() {
        return (FAKE_CONSTANT == (FAKE_CONSTANT & kind));
    }

    public void markStringKind() {
        kind = CONSTANT | STRING | kind;
    }

    public void markConstantKind() {
        kind = CONSTANT | kind;
    }

    public void markFakeStringKind() {
        kind = CONSTANT | FAKE_STRING | kind;
    }

    public void markFakeConstantKind() {
        kind = CONSTANT | FAKE_CONSTANT | kind;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder("[ID: ");
        builder.append(getGraphNodeId());
        builder.append("] <");
        builder.append(cgNode.getMethod().getSignature());
        builder.append(">");
        builder.append("v");
        builder.append(value);
        SSAInstruction inst = cgNode.getDU().getDef(value);
        builder.append(getName(inst));
        if (isField()) {
            builder.append(": ");
            if (isStaticField()) {
                builder.append(fieldRef.toString());
            } else {
                builder.append('v');
                builder.append(obj);
                builder.append('.');
                builder.append(fieldRef.getName().toString());
            }
        }

        return builder.toString();
    }

    private String getName(SSAInstruction instruction) {
        if (instruction == null) {
            return "";
        }
        StringBuilder name = new StringBuilder();
        if (instruction.hasDef() && instruction.iIndex() >= 0) {
            String[] names = cgNode.getIR().getLocalNames(instruction.iIndex(), instruction.getDef());
            if (names != null && names.length > 0) {
                name = new StringBuilder("[").append(names[0]);
                for (int i = 1; i < names.length; i++) {
                    name.append(", ").append(names[i]);
                }
                name.append("]");
            }
        }
        return name.toString();
    }


    public boolean equals(Object obj) {
        if (obj instanceof TypingNode tgn) {
            return (tgn.value == this.value && tgn.cgNode.equals(this.cgNode));
        }
        return false;
    }

    public int hashCode() {
        return value + cgNode.hashCode() * 79;
    }

    public void joke() {
        // do nothing
    }

    public void markSpecial() {
        isSpecial = true;
    }

    public boolean isSpecialNode() {
        return isSpecial;
    }

    public int getKind() {
        return kind;
    }

    public void setKind(int kind) {
        this.kind = kind;
    }

    public CGNode getCgNode() {
        return cgNode;
    }

    public int getValue() {
        return value;
    }

    public FieldReference getFieldRef() {
        return fieldRef;
    }

}
