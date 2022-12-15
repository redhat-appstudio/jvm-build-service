package com.redhat.hacbs.container.verifier.asm;

import static java.util.Locale.ENGLISH;
import static org.objectweb.asm.Opcodes.ACC_DEPRECATED;
import static org.objectweb.asm.Opcodes.ACC_ENUM;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_MANDATED;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACC_TRANSIENT;
import static org.objectweb.asm.Opcodes.ACC_VOLATILE;

public enum FieldAccess implements Access {
    PUBLIC(ACC_PUBLIC),
    PRIVATE(ACC_PRIVATE),
    PROTECTED(ACC_PROTECTED),
    STATIC(ACC_STATIC),
    FINAL(ACC_FINAL),
    VOLATILE(ACC_VOLATILE),
    TRANSIENT(ACC_TRANSIENT),
    SYNTHETIC(ACC_SYNTHETIC),
    ENUM(ACC_ENUM),
    MANDATED(ACC_MANDATED),
    DEPRECATED(ACC_DEPRECATED);

    private final int access;

    FieldAccess(int access) {
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
