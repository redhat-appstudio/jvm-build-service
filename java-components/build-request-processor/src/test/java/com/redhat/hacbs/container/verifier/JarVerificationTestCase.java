package com.redhat.hacbs.container.verifier;

import static com.redhat.hacbs.container.verifier.JarVerifierUtils.runTests;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class JarVerificationTestCase {

    @Test
    void testNoChanges() {
        runTests(SimpleClass.class, (s) -> s, List.of());
    }

    @Test
    void testRemovePublicFields() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (Modifier.isPublic(access)) {
                    return null;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }
        }, List.of(new ExpectedChange(ChangeType.REMOVE, "field:intField")));
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (Modifier.isPublic(access)) {
                    return null;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }
        }, List.of(), "-:.*:" + SimpleClass.class.getName() + ":field:intField");
    }

    @Test
    void testChangePublicField() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (Objects.equals(name, "intField") && Objects.isNull(value)) {
                    return super.visitField(access, name, descriptor, signature, 1);
                }
                return super.visitField(access, name, descriptor, signature, value);
            }
        }, List.of(), "^:.*::com.redhat.hacbs.container.verifier.SimpleClass|intField:null>1");

    }

    @Test
    void testChangeBytecodeVersion() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version - 1, access, name, signature, superName, interfaces);
            }

        }, List.of(new ExpectedChange(ChangeType.MODIFY, "version:65.0>64.0")));
    }

    @Test
    void testChangeSuperclass() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, access, name, signature, "com/foo/SuperClass", interfaces);
            }

        }, List.of(new ExpectedChange(ChangeType.MODIFY, "superName:java/lang/Object>com/foo/SuperClass")));
    }

    @Test
    void testChangeAccess() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, access | Modifier.FINAL, name, signature, superName, interfaces);
            }

        }, List.of(new ExpectedChange(ChangeType.MODIFY, "access:public super deprecated>public final super deprecated")));
    }

    @Test
    void testChangeInterfaces() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, access, name, signature, superName,
                        new String[] { Runnable.class.getName(), Callable.class.getName() });
            }

        }, List.of(new ExpectedChange(ChangeType.MODIFY, "interfaces:[]>[java.lang.Runnable, java.util.concurrent.Callable]")));

        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, access, name, signature, superName,
                        new String[] { Runnable.class.getName(), Callable.class.getName() });
            }

        }, List.of(new ExpectedChange(ChangeType.MODIFY, "interfaces:[]>[java.lang.Runnable, java.util.concurrent.Callable]")));
    }

    @Test
    void testChangeSignature() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, access, name, "Ljava/lang/Object;", superName, interfaces);
            }

        }, List.of(new ExpectedChange(ChangeType.MODIFY, "signature:null>java.lang.Object")));
    }

    @Test
    void testRemovePublicMethods() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (Modifier.isPublic(access)) {
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, List.of(new ExpectedChange(ChangeType.REMOVE, "method:<init>"),
                new ExpectedChange(ChangeType.REMOVE, "method:publicMethod")));
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (Modifier.isPublic(access)) {
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, List.of(), "-:.*:com.redhat.hacbs.container.verifier.SimpleClass:method:<init>",
                "-:.*:com.redhat.hacbs.container.verifier.SimpleClass:method:publicMethod");
    }

    @Test
    void testClassAnnotations() {
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (visible && Objects.equals(descriptor, "Ljava/lang/Deprecated;")) {
                    return super.visitAnnotation("Ltest/Test;", true);
                }
                return super.visitAnnotation(descriptor, visible);
            }
        }, List.of(new ExpectedChange(ChangeType.ADD, "annotation:@test.Test"),
                new ExpectedChange(ChangeType.REMOVE, "annotation:@java.lang.Deprecated")));
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (visible) {
                    return null;
                }
                return super.visitAnnotation(descriptor, visible);
            }
        }, List.of(new ExpectedChange(ChangeType.REMOVE, "annotation:@java.lang.Deprecated")));
        runTests(SimpleClass.class, (s) -> new ClassVisitor(Opcodes.ASM9, s) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (visible) {
                    return null;
                }
                return super.visitAnnotation(descriptor, visible);
            }
        }, List.of(), "-:.*:com.redhat.hacbs.container.verifier.SimpleClass:annotation:@java.lang.Deprecated");
    }

}
