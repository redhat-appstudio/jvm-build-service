package com.redhat.hacbs.container.verifier;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import com.redhat.hacbs.container.verifier.asm.JarInfo;

public class JarVerifierUtils {

    public static void runTests(Class<?> baseClass, Function<ClassVisitor, ClassVisitor> visitorFunction,
            List<ExpectedChange> expectedChanges,
            String... exclusions) {
        var name = baseClass.getSimpleName() + ".class";

        try (var in = baseClass.getResourceAsStream(name)) {
            Assertions.assertNotNull(in);
            var dir = Files.createTempDirectory("tests");
            var jar1Path = dir.resolve("tmp1.jar");
            var jar2Path = dir.resolve("tmp2.jar");
            var classData = in.readAllBytes();

            try (var out = new JarOutputStream(Files.newOutputStream(jar1Path))) {
                var zipEntry = new ZipEntry(baseClass.getName().replace(".", "/") + ".class");
                zipEntry.setSize(classData.length);
                out.putNextEntry(zipEntry);
                out.write(classData);
                out.closeEntry();
            }

            var classReader = new ClassReader(classData);
            var writer = new ClassWriter(classReader, 0);
            var visitor = visitorFunction.apply(writer);
            classReader.accept(visitor, 0);
            var modClassData = writer.toByteArray();

            try (var out = new JarOutputStream(Files.newOutputStream(jar2Path))) {
                var zipEntry = new ZipEntry(baseClass.getName().replace(".", "/") + ".class");
                zipEntry.setSize(modClassData.length);
                out.putNextEntry(zipEntry);
                out.write(modClassData);
                out.closeEntry();
            }

            var left = new JarInfo(jar1Path);
            var right = new JarInfo(jar2Path);
            Set<String> expected = new HashSet<>();
            for (var i : expectedChanges) {
                StringBuilder sb = new StringBuilder();
                if (i.changeType() == ChangeType.ADD) {
                    sb.append("+");
                } else if (i.changeType() == ChangeType.REMOVE) {
                    sb.append("-");
                } else {
                    sb.append("^");
                }
                sb.append(":tmp1.jar:");
                sb.append(baseClass.getName());
                sb.append(":");
                sb.append(i.change());
                expected.add(sb.toString());
            }
            Assertions.assertEquals(expected,
                    new HashSet<>(left.diffJar(right, Arrays.stream(exclusions).collect(Collectors.toList()))));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
