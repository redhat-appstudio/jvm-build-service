package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.accessToSet;

import java.util.Set;

import org.objectweb.asm.tree.ModuleRequireNode;

public record ModuleRequireInfo(String module, Set<ModuleAccess> access,
        String version) implements AsmDiffable<ModuleRequireInfo> {
    public ModuleRequireInfo(ModuleRequireNode node) {
        this(node.module, accessToSet(node.access, ModuleAccess.class), node.version);
    }
}
