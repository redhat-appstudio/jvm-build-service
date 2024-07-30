package com.redhat.hacbs.container.verifier.asm;

import static java.lang.System.Logger.Level.DEBUG;
import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;

import java.lang.System.Logger;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;

public final class AsmUtils {
    private static final Logger LOGGER = System.getLogger(AsmUtils.class.getName());

    private static final String INIT = "<init>";

    private static final Pattern SLASH_DOLLAR_PATTERN = Pattern.compile("[/$]");

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

    public static String fixName(String name) {
        return SLASH_DOLLAR_PATTERN.matcher(name).replaceAll(".");
    }

    public static String signatureToJavaCode(String name, String desc, String signature, List<String> exceptions) {
        LOGGER.log(DEBUG, "var name = \"{0}\";", name);
        LOGGER.log(DEBUG, "var desc = \"{0}\";", desc);
        LOGGER.log(DEBUG, "var signature = \"{0}\";", signature);

        if (LOGGER.isLoggable(DEBUG)) {
            LOGGER.log(DEBUG, "var exceptions = {0};", exceptions != null ? (!exceptions.isEmpty() ? "List.of(" + (exceptions.stream().map(s -> '"' + fixName(s) + '"').collect(Collectors.joining(", "))) + ")" : "List<String>.of()") : "\"null\"");
        }

        if (signature == null) {
            signature = desc;
        }

        if (signature == null) {
            return null;
        }

        var sb = new StringBuilder();
        var reader = new SignatureReader(signature);
        var writer = new JavaCodeSignatureWriter();
        reader.accept(writer);

        if (!signature.contains("(") && !signature.contains("<")) {
            var typeReader = new SignatureReader(signature);
            var typeWriter = new JavaCodeSignatureWriter();
            typeReader.acceptType(typeWriter);
            sb.append(typeWriter);
            return sb.toString();
        } else {
            if (desc != null && !desc.contains("(")) {
                sb.append(fixName(Type.getType(desc).getClassName()));
            }
        }

        if (!writer.getFormals().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }

            sb.append(writer.getFormals()).append("> ");
        }

        if (name != null && !INIT.equals(name)) {
            if (writer.getReturnType() == null) {
                if (desc != null) {
                    sb.append(fixName(Type.getReturnType(desc).getClassName())).append(' ');
                }
            } else {
                sb.append(writer.getReturnType().toString()).append(' ');
            }
        }

        if (name != null) {
            if (INIT.equals(name)) {
                sb.append('(');
            } else {
                sb.append(fixName(name)).append('(');
            }
        }

        if (!writer.getParameters().isEmpty()) {
            sb.append(writer.getParameters().stream().map(JavaCodeSignatureWriter::toString).collect(Collectors.joining(", "))).append(')');
        } else {
            sb.append(')');
        }

        if (exceptions != null && !exceptions.isEmpty()) {
            sb.append(" throws ").append(exceptions.stream().map(AsmUtils::fixName).collect(Collectors.joining(", ")));
        }

        LOGGER.log(DEBUG, "var expected = \"{0}\";", sb);
        return sb.toString();
    }
}
