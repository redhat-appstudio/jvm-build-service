package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.ModuleRequireNode;

public record ModuleRequireInfo(String module, AccessSet<ModuleAccess> access,
        String version) implements AsmDiffable<ModuleRequireInfo> {
    public ModuleRequireInfo(ModuleRequireNode node) {
        this(node.module, new AccessSet<>(node.access, ModuleAccess.class), node.version);
    }
}
