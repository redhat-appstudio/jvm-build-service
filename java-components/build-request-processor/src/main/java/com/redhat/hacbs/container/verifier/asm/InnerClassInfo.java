package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.InnerClassNode;

public record InnerClassInfo(String name, String outerName, String innerName,
        AccessSet<ClassAccess> access) implements AsmDiffable<InnerClassInfo> {
    public InnerClassInfo(InnerClassNode node) {
        this(node.name, node.outerName, node.innerName, new AccessSet<>(node.access, ClassAccess.class));
    }

    @Override
    public String getName() {
        return name;
    }
}
