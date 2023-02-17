package com.redhat.hacbs.classfile.tracker;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClassTrackingModificationTestCase {

    public static final TrackingData DATA = new TrackingData("com.acme:acme:1.0", "rebuilt", Map.of("a", "b"));
    public static final TrackingData CHANGED_DATA = new TrackingData("com.acme:acme:1.0", "central", Map.of("foo", "bar"));

    @Test
    public void testBytecodeClassLevelTracking() throws Exception {
        byte[] thisClass = getClass().getResourceAsStream(getClass().getSimpleName() + ".class").readAllBytes();
        var results = ClassFileTracker.addTrackingDataToClass(thisClass, DATA, "test");
        Assertions.assertEquals(DATA, ClassFileTracker.readTrackingInformationFromClass(results));
        results = ClassFileTracker.addTrackingDataToClass(results, CHANGED_DATA, "changed-name");
        Assertions.assertEquals(DATA, ClassFileTracker.readTrackingInformationFromClass(results));
    }

    @Test
    public void testBytecodeJarLevelTracking() throws Exception {
        byte[] thisClass = getClass().getResourceAsStream(getClass().getSimpleName() + ".class").readAllBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new JarEntry(getClass().getPackageName().replace(".", "/") + getClass().getSimpleName() + ".class"));
        zip.write(thisClass);
        zip.close();

        var results = ClassFileTracker.addTrackingDataToJar(out.toByteArray(), DATA);
        Assertions.assertEquals(Collections.singleton(DATA), ClassFileTracker.readTrackingDataFromJar(results, "test.jar"));
    }
}
