package com.redhat.hacbs.container.verifier.asm;

import java.util.List;

import org.objectweb.asm.tree.ModuleExportNode;

public record ModuleExportInfo(String packaze, AccessSet<ModuleAccess> access,
        List<String> modules) implements AsmDiffable<ModuleExportInfo> {
    public ModuleExportInfo(ModuleExportNode node) {
        this(node.packaze, new AccessSet<>(node.access, ModuleAccess.class),
                node.modules != null ? List.copyOf(node.modules) : null);
    }
}
