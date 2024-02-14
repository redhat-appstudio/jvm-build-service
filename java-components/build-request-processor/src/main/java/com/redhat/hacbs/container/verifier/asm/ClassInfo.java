package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.isPublic;
import static com.redhat.hacbs.container.verifier.asm.AsmUtils.isSyntheticBridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.objectweb.asm.tree.ClassNode;

public record ClassInfo(ClassVersion version, AccessSet<ClassAccess> access, String name, String signature, String superName,
        List<String> interfaces, String sourceFile, String sourceDebug, Map<String, ModuleInfo> module, String outerClass,
        String outerMethod, String outerMethodDesc, Map<String, AnnotationInfo> visibleAnnotations,
        /* Map<String, AnnotationInfo> invisibleAnnotations, *//* Map<String, TypeAnnotationInfo> visibleTypeAnnotations, */
        /* Map<String, TypeAnnotationInfo> invisibleTypeAnnotations, *//* List<AttributeInfo> attrs, */
        /* Map<String, InnerClassInfo> innerClasses, */ String nestHostClass, List<String> nestMembers,
        List<String> permittedSubclasses, Map<String, RecordComponentInfo> recordComponents, Map<String, FieldInfo> fields,
        Map<String, MethodInfo> methods) implements AsmDiffable<ClassInfo> {
    public ClassInfo(ClassNode node) {
        this(new ClassVersion(node.version), new AccessSet<>(node.access, ClassAccess.class), node.name, node.signature,
                node.superName,
                List.copyOf(node.interfaces), node.sourceFile, node.sourceDebug,
                node.module != null ? Collections.singletonMap(node.module.name, new ModuleInfo(node.module))
                        : Collections.emptyMap(),
                node.outerClass, node.outerMethod,
                node.outerMethodDesc,
                node.visibleAnnotations != null
                        ? node.visibleAnnotations.stream()
                                .collect(Collectors.toMap(n -> n.desc, AnnotationInfo::new, (x, y) -> x, LinkedHashMap::new))
                        : Collections.emptyMap(),
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
                /* node.attrs != null ? node.attrs.stream().map(AttributeInfo::new).collect(Collectors.toList()) : null, */
                /*
                 * node.innerClasses.stream()
                 * .collect(Collectors.toMap(n -> n.name, InnerClassInfo::new, (x, y) -> x, LinkedHashMap::new)),
                 */
                node.nestHostClass, node.nestMembers != null ? List.copyOf(node.nestMembers) : null,
                node.permittedSubclasses != null ? List.copyOf(node.permittedSubclasses) : null,
                node.recordComponents != null ? node.recordComponents.stream()
                        .collect(Collectors.toMap(n -> n.name + n.descriptor, RecordComponentInfo::new, (x, y) -> x,
                                LinkedHashMap::new))
                        : Collections.emptyMap(),
                node.fields.stream().filter(field -> isPublic(field.access))
                        .collect(Collectors.toMap(n -> n.name, FieldInfo::new, (x, y) -> x, LinkedHashMap::new)),
                node.methods.stream().filter(method -> isPublic(method.access) && !isSyntheticBridge(method.access))
                        .collect(Collectors.toMap(n -> n.name + n.desc, MethodInfo::new, (x, y) -> x, LinkedHashMap::new)));
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
