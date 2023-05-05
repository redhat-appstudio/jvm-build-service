package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.accessToSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.ModuleNode;

public record ModuleInfo(String name, Set<ModuleAccess> access, String version, String mainClass, List<String> packages,
        Map<String, ModuleRequireInfo> requires, Map<String, ModuleExportInfo> exports, Map<String, ModuleOpenInfo> opens,
        List<String> uses, Map<String, ModuleProvideInfo> provides) implements AsmDiffable<ModuleInfo> {
    public ModuleInfo(ModuleNode node) {
        this(node.name, accessToSet(node.access, ModuleAccess.class), node.version, node.mainClass,
                node.packages != null ? List.copyOf(node.packages) : null,
                node.requires != null ? node.requires.stream()
                        .collect(Collectors.toMap(n -> n.module, ModuleRequireInfo::new, (x, y) -> x, LinkedHashMap::new))
                        : null,
                node.exports != null ? node.exports.stream()
                        .collect(Collectors.toMap(n -> n.packaze, ModuleExportInfo::new, (x, y) -> x, LinkedHashMap::new))
                        : null,
                node.opens != null ? node.opens.stream()
                        .collect(Collectors.toMap(n -> n.packaze, ModuleOpenInfo::new, (x, y) -> x, LinkedHashMap::new)) : null,
                node.uses != null ? List.copyOf(node.uses) : null, node.provides != null ? node.provides.stream()
                        .collect(Collectors.toMap(n -> n.service, ModuleProvideInfo::new, (x, y) -> x, LinkedHashMap::new))
                        : null);
    }

    @Override
    public String getName() {
        return name;
    }
}
