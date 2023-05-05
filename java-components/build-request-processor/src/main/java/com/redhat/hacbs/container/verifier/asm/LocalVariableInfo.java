package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.LocalVariableNode;

public record LocalVariableInfo(String name, String desc, String signature, LabelInfo start, LabelInfo end,
        int index) implements AsmDiffable<LocalVariableInfo> {
    public LocalVariableInfo(LocalVariableNode node) {
        this(node.name, node.desc, node.signature, new LabelInfo(node.start), new LabelInfo(node.end), node.index);
    }

    @Override
    public String getName() {
        return name;
    }
}
