package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.ParameterNode;

public record ParameterInfo(String name, AccessSet<ParameterAccess> access) implements AsmDiffable<ParameterInfo> {
    public ParameterInfo(ParameterNode node) {
        this(node.name, new AccessSet<>(node.access, ParameterAccess.class));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return getName();
    }
}
