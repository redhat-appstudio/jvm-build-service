package com.redhat.hacbs.container.verifier.asm;

import static java.util.Locale.ENGLISH;
import static org.objectweb.asm.Opcodes.ACC_MANDATED;
import static org.objectweb.asm.Opcodes.ACC_OPEN;
import static org.objectweb.asm.Opcodes.ACC_STATIC_PHASE;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACC_TRANSITIVE;

public enum ModuleAccess implements Access {
    OPEN(ACC_OPEN),
    TRANSITIVE(ACC_TRANSITIVE),
    STATIC_PHASE(ACC_STATIC_PHASE),
    SYNTHETIC(ACC_SYNTHETIC),
    MANDATED(ACC_MANDATED);

    private final int access;

    ModuleAccess(int access) {
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
