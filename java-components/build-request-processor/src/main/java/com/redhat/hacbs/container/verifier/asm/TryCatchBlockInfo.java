package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.tree.TryCatchBlockNode;

public record TryCatchBlockInfo(LabelInfo start, LabelInfo end, LabelInfo handler,
        String type/*
                    * ,
                    * Map<String, TypeAnnotationInfo> visibleTypeAnnotations,
                    * Map<String, TypeAnnotationInfo> invisibleTypeAnnotations
                    */) implements AsmDiffable<TypeAnnotationInfo> {
    public TryCatchBlockInfo(TryCatchBlockNode node) {
        this(new LabelInfo(node.start), new LabelInfo(node.end), new LabelInfo(node.handler), node.type/*
                                                                                                        * ,
                                                                                                        * node.
                                                                                                        * visibleTypeAnnotations
                                                                                                        * != null ? node.
                                                                                                        * visibleTypeAnnotations
                                                                                                        * .stream().collect(
                                                                                                        * Collectors.toMap(n ->
                                                                                                        * n.desc,
                                                                                                        * TypeAnnotationInfo::
                                                                                                        * new, (x, y) -> x,
                                                                                                        * LinkedHashMap::new)) :
                                                                                                        * null,
                                                                                                        * node.
                                                                                                        * invisibleTypeAnnotations
                                                                                                        * != null
                                                                                                        * ? node.
                                                                                                        * invisibleTypeAnnotations
                                                                                                        * .stream().collect(
                                                                                                        * Collectors.toMap(n ->
                                                                                                        * n.desc,
                                                                                                        * TypeAnnotationInfo::
                                                                                                        * new, (x, y) -> x,
                                                                                                        * LinkedHashMap::new))
                                                                                                        * : null
                                                                                                        */);
    }
}
