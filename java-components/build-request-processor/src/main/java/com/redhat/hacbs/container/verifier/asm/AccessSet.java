package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.accessSetToString;
import static com.redhat.hacbs.container.verifier.asm.AsmUtils.accessToSet;

import java.util.Objects;
import java.util.Set;

public class AccessSet<E extends Enum<E> & Access> {
    private final Set<E> set;

    public AccessSet(int access, Class<E> clazz) {
        this.set = accessToSet(access, clazz);
    }

    public boolean hasFlag(E flag) {
        return set.contains(flag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        AccessSet<?> accessSet = (AccessSet<?>) o;
        return Objects.equals(set, accessSet.set);
    }

    @Override
    public int hashCode() {
        return Objects.hash(set);
    }

    @Override
    public String toString() {
        return accessSetToString(set);
    }
}
