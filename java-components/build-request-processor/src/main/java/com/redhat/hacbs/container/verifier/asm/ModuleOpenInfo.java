package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.accessToSet;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ModuleOpenNode;

public record ModuleOpenInfo(String packaze, Set<ModuleAccess> access,
        List<String> modules) implements AsmDiffable<ModuleOpenInfo> {
    public ModuleOpenInfo(ModuleOpenNode node) {
        this(node.packaze, accessToSet(node.access, ModuleAccess.class),
                node.modules != null ? List.copyOf(node.modules) : null);
    }
}
