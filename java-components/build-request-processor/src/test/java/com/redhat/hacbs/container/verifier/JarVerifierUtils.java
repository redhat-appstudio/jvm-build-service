package com.redhat.hacbs.container.verifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import com.redhat.hacbs.container.verifier.asm.JarInfo;

public class JarVerifierUtils {

    public static void runTests(Class<?> baseClass, Function<ClassVisitor, ClassVisitor> visitorFunction, int expected,
            String... exclusions) {
        try (InputStream in = baseClass.getResourceAsStream(baseClass.getSimpleName() + ".class")) {
            var dir = Files.createTempDirectory("tests");
            var jar1Path = dir.resolve("tmp1.jar");
            var jar2Path = dir.resolve("tmp2.jar");
            byte[] classData = in.readAllBytes();

            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar1Path))) {
                ZipEntry zipEntry = new ZipEntry(baseClass.getName().replace(".", "/") + ".class");
                zipEntry.setSize(classData.length);
                out.putNextEntry(zipEntry);
                out.write(classData);
                out.closeEntry();
            }

            ClassReader classReader = new ClassReader(classData);
            ClassWriter writer = new ClassWriter(classReader, 0);
            var visitor = visitorFunction.apply(writer);
            classReader.accept(visitor, 0);
            var modClassData = writer.toByteArray();

            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar2Path))) {
                ZipEntry zipEntry = new ZipEntry(baseClass.getName().replace(".", "/") + ".class");
                zipEntry.setSize(modClassData.length);
                out.putNextEntry(zipEntry);
                out.write(modClassData);
                out.closeEntry();
            }

            JarInfo left = new JarInfo(jar1Path);
            JarInfo right = new JarInfo(jar2Path);
            Assertions.assertEquals(expected, left.diffJar(right, Arrays.stream(exclusions).toList()));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
