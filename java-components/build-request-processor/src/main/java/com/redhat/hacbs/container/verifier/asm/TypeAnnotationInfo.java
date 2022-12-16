package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.TypeAnnotationNode;

public record TypeAnnotationInfo(String desc/* , List<Object> values */, int typeRef,
        String typePath) implements AsmDiffable<TypeAnnotationInfo> {
    public TypeAnnotationInfo(TypeAnnotationNode node) {
        this(node.desc/* , node.values != null ? List.copyOf(node.values) : null */, node.typeRef,
                node.typePath != null ? node.typePath.toString() : null);
    }
}
