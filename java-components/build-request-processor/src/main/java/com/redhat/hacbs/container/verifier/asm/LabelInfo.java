package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.LabelNode;

public record LabelInfo(String value) implements AsmDiffable<LabelInfo> {
    public LabelInfo(LabelNode node) {
        this(node.getLabel().toString());
    }

    @Override
    public String getName() {
        return value;
    }

    @Override
    public String toString() {
        return getName();
    }
}
