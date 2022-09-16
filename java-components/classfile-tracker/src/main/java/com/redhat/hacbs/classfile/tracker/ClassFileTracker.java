package com.redhat.hacbs.classfile.tracker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class ClassFileTracker {

    public static byte[] addTrackingDataToClass(byte[] classData, TrackingData data, String name) {
        try {
            ClassReader classReader = new ClassReader(classData);
            ClassWriter writer = new ClassWriter(classReader, 0);
            ClassTrackingWriteDataVisitor classTrackingVisitor = new ClassTrackingWriteDataVisitor(Opcodes.ASM9, writer, data);
            classReader.accept(classTrackingVisitor, 0);
            return writer.toByteArray();
        } catch (Exception e) {
            Logger.getLogger(ClassFileTracker.class.getName()).log(Level.SEVERE,
                    "Failed to add tracking data to class: " + name, e);
            return classData;
        }
    }

    public static TrackingData readTrackingInformationFromClass(byte[] classData) {
        ClassReader classReader = new ClassReader(classData);
        ClassTrackingReadDataVisitor classTrackingVisitor = new ClassTrackingReadDataVisitor(Opcodes.ASM9);
        classReader.accept(classTrackingVisitor, new Attribute[] { new ClassFileSourceAttribute(null) }, 0);
        return classTrackingVisitor.getContents();
    }

    public static byte[] addTrackingDataToJar(byte[] input, TrackingData data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        addTrackingDataToJar(new ByteArrayInputStream(input), data, out);
        return out.toByteArray();
    }

    public static void addTrackingDataToJar(InputStream input, TrackingData data, OutputStream out)
            throws IOException, ZipException {
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zipIn = new ZipInputStream(input)) {
            try (ZipOutputStream zipOut = new ZipOutputStream(out)) {
                var entry = zipIn.getNextEntry();
                while (entry != null) {
                    if (!seen.contains(entry.getName())) {
                        seen.add(entry.getName());
                        if (entry.getName().endsWith(".class")) {
                            ZipEntry newEntry = new ZipEntry(entry.getName());
                            if (entry.getLastAccessTime() != null) {
                                newEntry.setLastAccessTime(entry.getLastAccessTime());
                            }
                            if (entry.getLastModifiedTime() != null) {
                                newEntry.setLastModifiedTime(entry.getLastModifiedTime());
                            }
                            byte[] modified = addTrackingDataToClass(zipIn.readAllBytes(), data, entry.getName());
                            newEntry.setSize(modified.length);
                            zipOut.putNextEntry(newEntry);
                            zipOut.write(modified);
                        } else if (entry.getName().endsWith(".jar")) {
                            ZipEntry newEntry = new ZipEntry(entry.getName());
                            if (entry.getLastAccessTime() != null) {
                                newEntry.setLastAccessTime(entry.getLastAccessTime());
                            }
                            if (entry.getLastModifiedTime() != null) {
                                newEntry.setLastModifiedTime(entry.getLastModifiedTime());
                            }
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            addTrackingDataToJar(new NoCloseInputStream(zipIn), data, baos);
                            byte[] modified = baos.toByteArray();
                            newEntry.setSize(modified.length);
                            zipOut.putNextEntry(newEntry);
                            zipOut.write(modified);
                        } else if (!isBlockOrSF(entry.getName())) {
                            zipOut.putNextEntry(entry);
                            zipOut.write(zipIn.readAllBytes());
                        }
                    }
                    entry = zipIn.getNextEntry();
                }
            }
        }
    }

    // same as the impl in sun.security.util.SignatureFileVerifier#isBlockOrSF()
    static boolean isBlockOrSF(final String s) {
        if (s == null) {
            return false;
        }
        return s.endsWith(".SF")
                || s.endsWith(".DSA")
                || s.endsWith(".RSA")
                || s.endsWith(".EC");
    }

    public static Set<TrackingData> readTrackingDataFromJar(byte[] input, String jarFile) throws IOException {
        return readTrackingDataFromJar(new ByteArrayInputStream(input), jarFile);
    }

    public static Set<TrackingData> readTrackingDataFromJar(InputStream input, String jarFile) throws IOException {
        Set<TrackingData> ret = new HashSet<>();
        try (ZipInputStream zipIn = new ZipInputStream(input)) {
            var entry = zipIn.getNextEntry();
            while (entry != null) {
                if (entry.getName().endsWith(".class")) {
                    try {
                        TrackingData data = readTrackingInformationFromClass(zipIn.readAllBytes());
                        if (data != null) {
                            ret.add(data);
                        }
                    } catch (Exception e) {
                        Logger.getLogger("dependency-analyser").log(Level.SEVERE,
                                "Failed to read class " + entry.getName() + " from " + jarFile, e);
                    }
                } else if (entry.getName().endsWith(".jar")) {
                    ret.addAll(readTrackingDataFromJar(new NoCloseInputStream(zipIn), entry.getName()));
                }
                entry = zipIn.getNextEntry();
            }
        }
        return ret;
    }

}
