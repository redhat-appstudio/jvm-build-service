package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.AbstractInsnNode;

public record InsnInfo(int opcode, int type/*
                                            * , Map<String, TypeAnnotationInfo> visibleTypeAnnotations,
                                            * Map<String, TypeAnnotationInfo> invisibleTypeAnnotations
                                            */) implements AsmDiffable<InsnInfo> {
    public InsnInfo(AbstractInsnNode node) {
        this(node.getOpcode(), node.getType()/*
                                              * ,
                                              * node.visibleTypeAnnotations != null ?
                                              * node.visibleTypeAnnotations.stream().collect(
                                              * Collectors.toMap(n -> n.desc, TypeAnnotationInfo::new, (x, y) -> x,
                                              * LinkedHashMap::new)) : null,
                                              * node.invisibleTypeAnnotations != null
                                              * ? node.invisibleTypeAnnotations.stream().collect(
                                              * Collectors.toMap(n -> n.desc, TypeAnnotationInfo::new, (x, y) -> x,
                                              * LinkedHashMap::new))
                                              * : null
                                              */);
    }
}
