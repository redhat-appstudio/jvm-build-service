package com.redhat.hacbs.container.verifier.asm;

import java.util.List;

import org.objectweb.asm.tree.ModuleOpenNode;

public record ModuleOpenInfo(String packaze, AccessSet<ModuleAccess> access,
        List<String> modules) implements AsmDiffable<ModuleOpenInfo> {
    public ModuleOpenInfo(ModuleOpenNode node) {
        this(node.packaze, new AccessSet<>(node.access, ModuleAccess.class),
                node.modules != null ? List.copyOf(node.modules) : null);
    }
}
