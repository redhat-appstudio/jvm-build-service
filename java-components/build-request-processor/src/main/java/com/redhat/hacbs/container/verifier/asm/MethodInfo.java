package com.redhat.hacbs.container.verifier.asm;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.MethodNode;

public record MethodInfo(AccessSet<MethodAccess> access, String name, String desc, String signature, List<String> exceptions,
        Map<String, ParameterInfo> parameters, Map<String, AnnotationInfo> visibleAnnotations,
        /*
         * Map<String, AnnotationInfo> invisibleAnnotations, Map<String, TypeAnnotationInfo> visibleTypeAnnotations,
         * Map<String, TypeAnnotationInfo> invisibleTypeAnnotations, List<AttributeInfo> attrs, Object annotationDefault,
         */
        int visibleAnnotableParameterCount, List<Map<String, AnnotationInfo>> visibleParameterAnnotations/*
                                                                                                          * ,
                                                                                                          * int
                                                                                                          * invisibleAnnotableParameterCount,
                                                                                                          * List<Map<String,
                                                                                                          * AnnotationInfo>>
                                                                                                          * invisibleParameterAnnotations,
                                                                                                          */
/*
 * Map<Integer, InsnInfo> instructions, List<TryCatchBlockInfo> tryCatchBlocks, int maxStack, int maxLocals,
 * Map<String, LocalVariableInfo> localVariables, Map<String, LocalVariableAnnotationInfo> visibleLocalVariableAnnotations,
 * Map<String, LocalVariableAnnotationInfo> invisibleLocalVariableAnnotations
 */) implements AsmDiffable<MethodInfo> {
    public MethodInfo(MethodNode node) {
        this(new AccessSet<>((node.access & ACC_STATIC) != 0 ? (node.access & ~ACC_FINAL) : node.access, MethodAccess.class),
                node.name, node.desc, node.signature,
                List.copyOf(node.exceptions),
                node.parameters != null
                        ? node.parameters.stream().filter(p -> p.name == null && p.access == 0)
                                .collect(Collectors.toMap(n -> n.name, ParameterInfo::new, (x, y) -> x, LinkedHashMap::new))
                        : Collections.emptyMap(),
                node.visibleAnnotations != null
                        ? node.visibleAnnotations.stream()
                                .collect(Collectors.toMap(n -> n.desc, AnnotationInfo::new, (x, y) -> x, LinkedHashMap::new))
                        : null,
                /*
                 * node.invisibleAnnotations != null
                 * ? node.invisibleAnnotations.stream()
                 * .collect(Collectors.toMap(n -> n.desc, AnnotationInfo::new, (x, y) -> x, LinkedHashMap::new))
                 * : null,
                 */
                /*
                 * node.visibleTypeAnnotations != null ? node.visibleTypeAnnotations.stream().collect(
                 * Collectors.toMap(n -> n.desc, TypeAnnotationInfo::new, (x, y) -> x, LinkedHashMap::new)) : null,
                 */
                /*
                 * node.invisibleTypeAnnotations != null ? node.invisibleTypeAnnotations.stream().collect(
                 * Collectors.toMap(n -> n.desc, TypeAnnotationInfo::new, (x, y) -> x, LinkedHashMap::new)) : null,
                 */
                /*
                 * node.attrs != null ? node.attrs.stream().map(AttributeInfo::new).collect(Collectors.toList()) : null,
                 * node.annotationDefault,
                 */
                node.visibleAnnotableParameterCount,
                node.visibleParameterAnnotations != null ? Arrays.stream(node.visibleParameterAnnotations)
                        .filter(Objects::nonNull).map(m -> m.stream()
                                .collect(Collectors.toMap(n -> n.desc, AnnotationInfo::new, (x, y) -> x, LinkedHashMap::new)))
                        .collect(Collectors.toList()) : null/*
                                                             * ,
                                                             * node.invisibleAnnotableParameterCount,
                                                             * /*node.invisibleParameterAnnotations != null ?
                                                             * Arrays.stream(node.invisibleParameterAnnotations)
                                                             * .map(m -> m.stream()
                                                             * .collect(Collectors.toMap(n -> n.desc, AnnotationInfo::new, (x,
                                                             * y) -> x, LinkedHashMap::new)))
                                                             * .collect(Collectors.toList()) : null,
                                                             */
        /*
         * StreamSupport.stream(() -> node.instructions.spliterator(), ORDERED, false).collect(
         * Collectors.toMap(AbstractInsnNode::getOpcode, InsnInfo::new, (x, y) -> x, LinkedHashMap::new)),
         * node.tryCatchBlocks.stream().map(TryCatchBlockInfo::new).collect(Collectors.toList()),
         * node.maxStack,
         * node.maxLocals,
         * node.localVariables != null ? node.localVariables.stream()
         * .collect(Collectors.toMap(n -> n.name, LocalVariableInfo::new, (x, y) -> x, LinkedHashMap::new)) : null,
         * node.visibleLocalVariableAnnotations != null ? node.visibleLocalVariableAnnotations.stream()
         * .collect(Collectors.toMap(n -> n.desc, LocalVariableAnnotationInfo::new, (x, y) -> x,
         * LinkedHashMap::new))
         * : null,
         * node.invisibleLocalVariableAnnotations != null ? node.invisibleLocalVariableAnnotations.stream()
         * .collect(Collectors.toMap(n -> n.desc, LocalVariableAnnotationInfo::new, (x, y) -> x,
         * LinkedHashMap::new))
         * : null
         */);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return getName();
    }
}
