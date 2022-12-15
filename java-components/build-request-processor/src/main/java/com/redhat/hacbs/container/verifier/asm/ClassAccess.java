package com.redhat.hacbs.container.verifier.asm;

import static java.util.Locale.ENGLISH;
import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_ANNOTATION;
import static org.objectweb.asm.Opcodes.ACC_DEPRECATED;
import static org.objectweb.asm.Opcodes.ACC_ENUM;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_MODULE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_RECORD;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;

public enum ClassAccess implements Access {
    PUBLIC(ACC_PUBLIC),
    PRIVATE(ACC_PRIVATE),
    PROTECTED(ACC_PROTECTED),
    FINAL(ACC_FINAL),
    SUPER(ACC_SUPER),
    INTERFACE(ACC_INTERFACE),
    ABSTRACT(ACC_ABSTRACT),
    SYNTHETIC(ACC_SYNTHETIC),
    ANNOTATION(ACC_ANNOTATION),
    ENUM(ACC_ENUM),
    MODULE(ACC_MODULE),
    RECORD(ACC_RECORD),
    DEPRECATED(ACC_DEPRECATED);

    private final int access;

    ClassAccess(int access) {
        this.access = access;
    }

    public int getAccess() {
        return access;
    }

    @Override
    public String toString() {
        return name().toLowerCase(ENGLISH);
    }
}
