package com.redhat.hacbs.container.verifier.asm;

import static java.util.Locale.ENGLISH;
import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ACC_DEPRECATED;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_MANDATED;
import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_STRICT;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACC_VARARGS;

public enum MethodAccess implements Access {
    PUBLIC(ACC_PUBLIC),
    PRIVATE(ACC_PRIVATE),
    PROTECTED(ACC_PROTECTED),
    STATIC(ACC_STATIC),
    FINAL(ACC_FINAL),
    SYNCHRONIZED(ACC_SYNCHRONIZED),
    BRIDGE(ACC_BRIDGE),
    VARARGS(ACC_VARARGS),
    NATIVE(ACC_NATIVE),
    ABSTRACT(ACC_ABSTRACT),
    STRICT(ACC_STRICT),
    SYNTHETIC(ACC_SYNTHETIC),
    MANDATED(ACC_MANDATED),
    DEPRECATED(ACC_DEPRECATED);

    private final int access;

    MethodAccess(int access) {
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
