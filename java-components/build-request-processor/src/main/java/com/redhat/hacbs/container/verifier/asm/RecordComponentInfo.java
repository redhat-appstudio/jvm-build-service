package com.redhat.hacbs.container.verifier.asm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.RecordComponentNode;

public record RecordComponentInfo(String name, String descriptor, String signature,
        Map<String, AnnotationInfo> visibleAnnotations/*
                                                       * , Map<String, AnnotationInfo> invisibleAnnotations,
                                                       * Map<String, TypeAnnotationInfo> visibleTypeAnnotations
                                                       * , Map<String, TypeAnnotationInfo> invisibleTypeAnnotations,
                                                       * List<AttributeInfo> attrs
                                                       */) implements AsmDiffable<RecordComponentInfo> {
    public RecordComponentInfo(RecordComponentNode node) {
        this(node.name, node.descriptor, node.signature,
                node.visibleAnnotations != null
                        ? node.visibleAnnotations.stream()
                                .collect(Collectors.toMap(n -> n.desc, AnnotationInfo::new, (x, y) -> x, LinkedHashMap::new))
                        : null/*
                               * ,
                               * node.invisibleAnnotations != null
                               * ? node.invisibleAnnotations.stream()
                               * .collect(Collectors.toMap(n -> n.desc, AnnotationInfo::new, (x, y) -> x, LinkedHashMap::new))
                               * : null,
                               * node.visibleTypeAnnotations != null ? node.visibleTypeAnnotations.stream().collect(
                               * Collectors.toMap(n -> n.desc, TypeAnnotationInfo::new, (x, y) -> x, LinkedHashMap::new)) : null
                               * ,
                               * node.
                               * invisibleTypeAnnotations
                               * !=
                               * null ?
                               * node.
                               * invisibleTypeAnnotations
                               * .
                               * stream
                               * ().
                               * collect(
                               * Collectors
                               * .toMap
                               * (n ->
                               * n.
                               * desc,
                               * TypeAnnotationInfo
                               * ::new,
                               * (x, y)
                               * -> x,
                               * LinkedHashMap
                               * ::new)
                               * ) :
                               * null,
                               * node.
                               * attrs
                               * !=
                               * null ?
                               * node.
                               * attrs.
                               * stream
                               * ().map
                               * (
                               * AttributeInfo
                               * ::new)
                               * .
                               * collect
                               * (
                               * Collectors
                               * .
                               * toList
                               * ()) :
                               * null
                               */);
    }

    @Override
    public String getName() {
        return name;
    }
}
