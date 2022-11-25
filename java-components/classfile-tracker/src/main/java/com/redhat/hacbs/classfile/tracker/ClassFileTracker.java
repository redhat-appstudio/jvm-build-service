package com.redhat.hacbs.classfile.tracker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class ClassFileTracker {

    public static final Logger LOGGER = Logger.getLogger("dependency-analyser");

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
                        //TODO: kotlin inline methods mean some code from other archives end up in the final output
                        //I don't think we need to rebuild everything that has inlined code, as it means we will
                        //needs to build lots of different version of the kotlin standard library
                        if (!entry.getName().contains("$inlined$")) {
                            TrackingData data = readTrackingInformationFromClass(zipIn.readAllBytes());
                            if (data != null) {
                                ret.add(data);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE,
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

    public static Set<TrackingData> readTrackingDataFromFile(InputStream contents, String fileName) throws IOException {
        if (fileName.endsWith(".class")) {
            return Set.of(ClassFileTracker.readTrackingInformationFromClass(contents.readAllBytes()));
        } else if (fileName.endsWith(".jar")) {
            return ClassFileTracker.readTrackingDataFromJar(contents,
                    fileName);
        } else if (fileName.endsWith(".tgz") || fileName.endsWith(".tar.gaz")) {
            try {
                Set<TrackingData> ret = new HashSet<>();
                GZIPInputStream inputStream = new GZIPInputStream(contents);
                TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream);
                for (TarArchiveEntry entry = tarArchiveInputStream
                        .getNextTarEntry(); entry != null; entry = tarArchiveInputStream.getNextTarEntry()) {
                    ret.addAll(readTrackingDataFromFile(new NoCloseInputStream(tarArchiveInputStream), entry.getName()));
                }
                return ret;
            } catch (Exception e) {
                //we don't fail on archives
                LOGGER.log(Level.SEVERE, "Failed to analyse archive " + fileName, e);
            }
        } else if (fileName.endsWith(".tar")) {
            try {
                Set<TrackingData> ret = new HashSet<>();
                TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(contents);
                for (TarArchiveEntry entry = tarArchiveInputStream
                        .getNextTarEntry(); entry != null; entry = tarArchiveInputStream.getNextTarEntry()) {
                    ret.addAll(readTrackingDataFromFile(new NoCloseInputStream(tarArchiveInputStream), entry.getName()));
                }
                return ret;
            } catch (Exception e) {
                //we don't fail on archives
                LOGGER.log(Level.SEVERE, "Failed to analyse archive " + fileName, e);
            }
        } else if (fileName.endsWith(".zip")) {
            try {
                Set<TrackingData> ret = new HashSet<>();
                ZipInputStream tarArchiveInputStream = new ZipInputStream(contents);
                for (var entry = tarArchiveInputStream
                        .getNextEntry(); entry != null; entry = tarArchiveInputStream.getNextEntry()) {
                    ret.addAll(readTrackingDataFromFile(new NoCloseInputStream(tarArchiveInputStream), entry.getName()));
                }
                return ret;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to analyse archive " + fileName, e);
            }
        }
        return Collections.emptySet();
    }
}
