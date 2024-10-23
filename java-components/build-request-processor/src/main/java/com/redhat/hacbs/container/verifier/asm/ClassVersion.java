package com.redhat.hacbs.container.verifier.asm;

import static org.objectweb.asm.Opcodes.V10;
import static org.objectweb.asm.Opcodes.V11;
import static org.objectweb.asm.Opcodes.V12;
import static org.objectweb.asm.Opcodes.V13;
import static org.objectweb.asm.Opcodes.V14;
import static org.objectweb.asm.Opcodes.V15;
import static org.objectweb.asm.Opcodes.V16;
import static org.objectweb.asm.Opcodes.V17;
import static org.objectweb.asm.Opcodes.V18;
import static org.objectweb.asm.Opcodes.V19;
import static org.objectweb.asm.Opcodes.V1_1;
import static org.objectweb.asm.Opcodes.V1_2;
import static org.objectweb.asm.Opcodes.V1_3;
import static org.objectweb.asm.Opcodes.V1_4;
import static org.objectweb.asm.Opcodes.V1_5;
import static org.objectweb.asm.Opcodes.V1_6;
import static org.objectweb.asm.Opcodes.V1_7;
import static org.objectweb.asm.Opcodes.V1_8;
import static org.objectweb.asm.Opcodes.V20;
import static org.objectweb.asm.Opcodes.V21;
import static org.objectweb.asm.Opcodes.V22;
import static org.objectweb.asm.Opcodes.V23;
import static org.objectweb.asm.Opcodes.V9;

import com.redhat.hacbs.container.analyser.build.JavaVersion;

public record ClassVersion(int version, int majorVersion, int minorVersion,
        JavaVersion javaVersion) implements AsmDiffable<ClassVersion> {
    public ClassVersion(int version) {
        this(version, version & 0xFFFF, version >>> 16, toJavaVersion(version));
    }

    public static JavaVersion toJavaVersion(int classVersion) {
        var version = switch (classVersion) {
            case V1_1 -> "1.1";
            case V1_2 -> "1.2";
            case V1_3 -> "1.3";
            case V1_4 -> "1.4";
            case V1_5 -> "1.5";
            case V1_6 -> "1.6";
            case V1_7 -> "1.7";
            case V1_8 -> "8";
            case V9 -> "9";
            case V10 -> "10";
            case V11 -> "11";
            case V12 -> "12";
            case V13 -> "13";
            case V14 -> "14";
            case V15 -> "15";
            case V16 -> "16";
            case V17 -> "17";
            case V18 -> "18";
            case V19 -> "19";
            case V20 -> "20";
            case V21 -> "21";
            case V22 -> "22";
            case V23 -> "23";
            default -> throw new IllegalArgumentException("Unknown class version: " + classVersion);
        };
        return new JavaVersion(version);
    }

    public static JavaVersion toJavaVersion(ClassVersion version) {
        return toJavaVersion(version.version);
    }

    public static ClassVersion fromVersion(String s) {
        var v = s.split("\\.");

        if (v.length != 2) {
            throw new IllegalArgumentException("Invalid version: " + s);
        }

        var majorVersion = Integer.parseInt(v[0]);
        var minorVersion = Integer.parseInt(v[1]);
        var version = majorVersion | minorVersion << 16;
        return new ClassVersion(version);
    }

    @Override
    public String getName() {
        return majorVersion + "." + minorVersion;
    }

    @Override
    public String toString() {
        return majorVersion + "." + minorVersion + " (Java " + javaVersion.version() + ")";
    }
}
