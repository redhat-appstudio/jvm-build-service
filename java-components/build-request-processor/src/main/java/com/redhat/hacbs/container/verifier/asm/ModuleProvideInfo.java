package com.redhat.hacbs.container.verifier.asm;

import java.util.List;

import org.objectweb.asm.tree.ModuleProvideNode;

public record ModuleProvideInfo(String service, List<String> providers) implements AsmDiffable<ModuleProvideInfo> {
    public ModuleProvideInfo(ModuleProvideNode node) {
        this(node.service, List.copyOf(node.providers));
    }

    @Override
    public String getName() {
        return service + " " + providers;
    }

    @Override
    public String toString() {
        return getName();
    }
}
