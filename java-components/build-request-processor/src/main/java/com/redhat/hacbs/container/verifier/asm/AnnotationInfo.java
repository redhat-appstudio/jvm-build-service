package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.AnnotationNode;

public record AnnotationInfo(String desc/* , List<Object> values */) implements AsmDiffable<AnnotationInfo> {
    public AnnotationInfo(AnnotationNode node) {
        this(node.desc/* , node.values != null ? List.copyOf(node.values) : null */);
    }
}
