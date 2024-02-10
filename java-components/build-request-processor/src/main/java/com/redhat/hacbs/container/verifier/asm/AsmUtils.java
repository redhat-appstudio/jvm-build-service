package com.redhat.hacbs.container.verifier.asm;

import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ACC_ENUM;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class AsmUtils {
    private AsmUtils() {

    }

    private static int ordinal(int i) {
        return Integer.numberOfTrailingZeros(i);
    }

    public static <E extends Enum<E> & Access> Set<E> accessToSet(int access, Class<E> clazz) {
        return Arrays.stream(clazz.getEnumConstants()).filter(constant -> (access & (1 << ordinal(constant.getAccess()))) != 0)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(clazz)));
    }

    public static <E extends Enum<E> & Access> int setToAccess(Set<E> set) {
        return set.stream().mapToInt(constant -> 1 << ordinal(constant.getAccess())).reduce(0, (x, y) -> x | y);
    }

    public static <E extends Enum<E> & Access> String accessSetToString(Set<E> set) {
        var s = Objects.toString(set, "");

        if (s.length() >= 2) {
            return s.substring(1, s.length() - 1).replace(",", "");
        }

        return s;
    }

    public static boolean isPublic(int access) {
        return ((access & ACC_PUBLIC) != 0);
    }

    public static boolean isSyntheticBridge(int access) {
        var b = ACC_BRIDGE | ACC_SYNTHETIC;
        return ((access & b) == b);
    }

    public static boolean isEnumVaueOf(int access, String name) {
        return ((access & ACC_ENUM) != 0 && "valueOf".equals(name));
    }
}
