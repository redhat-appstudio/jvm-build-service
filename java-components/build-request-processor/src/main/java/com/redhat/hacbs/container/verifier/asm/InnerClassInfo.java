package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.accessToSet;

import java.util.Set;

import org.objectweb.asm.tree.InnerClassNode;

public record InnerClassInfo(String name, String outerName, String innerName,
        Set<ClassAccess> access) implements AsmDiffable<InnerClassInfo> {
    public InnerClassInfo(InnerClassNode node) {
        this(node.name, node.outerName, node.innerName, accessToSet(node.access, ClassAccess.class));
    }

    @Override
    public String getName() {
        return name;
    }
}
