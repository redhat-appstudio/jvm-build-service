package com.redhat.hacbs.container.verifier;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Modifier;

import static com.redhat.hacbs.container.verifier.JarVerifierUtils.runTests;

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
        }, 0,"-:.*:com.redhat.hacbs.container.verifier.SimpleClass:field:intField");
    }
}
