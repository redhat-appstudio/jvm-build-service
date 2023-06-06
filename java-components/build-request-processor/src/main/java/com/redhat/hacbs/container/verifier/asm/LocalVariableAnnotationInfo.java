package com.redhat.hacbs.container.verifier.asm;

import java.util.List;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.LocalVariableAnnotationNode;

public record LocalVariableAnnotationInfo(String desc, List<Object> values, int typeRef, String typePath, List<LabelInfo> start,
        List<LabelInfo> end, List<Integer> index) implements AsmDiffable<LocalVariableAnnotationInfo> {
    public LocalVariableAnnotationInfo(LocalVariableAnnotationNode node) {
        this(node.desc, node.values != null ? List.copyOf(node.values) : null, node.typeRef,
                node.typePath != null ? node.typePath.toString() : null,
                node.start.stream().map(LabelInfo::new).collect(Collectors.toList()),
                node.end.stream().map(LabelInfo::new).collect(Collectors.toList()), List.copyOf(node.index));
    }

    @Override
    public String getName() {
        return desc;
    }

    @Override
    public String toString() {
        return getName();
    }
}
