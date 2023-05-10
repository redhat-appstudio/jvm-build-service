package com.redhat.hacbs.container.verifier;

import static com.redhat.hacbs.container.verifier.JarVerifierUtils.runTests;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class JarVerificationTestCase {

    @Test
    public void testNoChanges() {
        runTests(SimpleClass.class, (s) -> s, 0);
    }

    @Test
    public void testRemovePublicFields() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (Modifier.isPublic(access)) {
                    return null;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }
        }, 1);
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (Modifier.isPublic(access)) {
                    return null;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }
        }, 0, "-:.*:com.redhat.hacbs.container.verifier.SimpleClass:field:intField");
    }

    @Test
    public void testRemovePublicMethods() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (Modifier.isPublic(access)) {
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 2);
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (Modifier.isPublic(access)) {
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0, "-:.*:com.redhat.hacbs.container.verifier.SimpleClass:method:<init>",
                "-:.*:com.redhat.hacbs.container.verifier.SimpleClass:method:publicMethod");
    }

    @Test
    public void testRemoveClassAnnotations() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (visible) {
                    return null;
                }
                System.out.println("descriptor: " + descriptor);
                return super.visitAnnotation(descriptor, visible);
            }
        }, 1);
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (visible) {
                    return null;
                }
                return super.visitAnnotation(descriptor, visible);
            }
        }, 0, "-:.*:com.redhat.hacbs.container.verifier.SimpleClass:annotation:java.lang.Deprecated");
    }
}
