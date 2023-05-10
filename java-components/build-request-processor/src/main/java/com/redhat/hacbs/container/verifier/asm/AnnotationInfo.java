package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.AnnotationNode;

public record AnnotationInfo(String desc/* , List<Object> values */) implements AsmDiffable<AnnotationInfo> {
    public AnnotationInfo(AnnotationNode node) {
        this(node.desc/* , node.values != null ? List.copyOf(node.values) : null */);
    }

    @Override
    public String getName() {
        return desc != null && desc.length() >= 2 ? desc.substring(1, desc.length() - 1) : desc;
    }
}
