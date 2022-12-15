package com.redhat.hacbs.container.verifier.asm;

import static java.util.Locale.ENGLISH;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_MANDATED;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;

public enum ParameterAccess implements Access {
    FINAL(ACC_FINAL),
    SYNTHETIC(ACC_SYNTHETIC),
    MANDATED(ACC_MANDATED);

    private final int access;

    ParameterAccess(int access) {
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
