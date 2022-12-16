package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.accessToSet;

import java.util.Set;

import org.objectweb.asm.tree.ParameterNode;

public record ParameterInfo(String name, Set<ParameterAccess> access) {
    public ParameterInfo(ParameterNode node) {
        this(node.name, accessToSet(node.access, ParameterAccess.class));
    }
}
