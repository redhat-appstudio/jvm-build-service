package com.redhat.hacbs.classfile.tracker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class ClassFileTracker {

    public static byte[] addTrackingDataToClass(byte[] classData, TrackingData data) {
        ClassReader classReader = new ClassReader(classData);
        ClassWriter writer = new ClassWriter(classReader, 0);
        ClassTrackingWriteDataVisitor classTrackingVisitor = new ClassTrackingWriteDataVisitor(Opcodes.ASM9, writer, data);
        classReader.accept(classTrackingVisitor, 0);
        return writer.toByteArray();
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
        try (ZipInputStream zipIn = new ZipInputStream(input)) {
            try (ZipOutputStream zipOut = new ZipOutputStream(out)) {
                var entry = zipIn.getNextEntry();
                while (entry != null) {
                    if (entry.getName().endsWith(".class")) {
                        ZipEntry newEntry = new ZipEntry(entry.getName());
                        if (entry.getLastAccessTime() != null) {
                            newEntry.setLastAccessTime(entry.getLastAccessTime());
                        }
                        if (entry.getLastModifiedTime() != null) {
                            newEntry.setLastModifiedTime(entry.getLastModifiedTime());
                        }
                        byte[] modified = addTrackingDataToClass(zipIn.readAllBytes(), data);
                        newEntry.setSize(modified.length);
                        zipOut.putNextEntry(newEntry);
                        zipOut.write(modified);
                    } else if (!isBlockOrSF(entry.getName())) {
                        zipOut.putNextEntry(entry);
                        zipOut.write(zipIn.readAllBytes());
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

    public static Set<TrackingData> readTrackingDataFromJar(byte[] input) throws IOException {
        Set<TrackingData> ret = new HashSet<>();
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(input))) {
            var entry = zipIn.getNextEntry();
            while (entry != null) {
                if (entry.getName().endsWith(".class")) {
                    ret.add(readTrackingInformationFromClass(zipIn.readAllBytes()));
                }
                entry = zipIn.getNextEntry();
            }
        }
        return ret;
    }

}
