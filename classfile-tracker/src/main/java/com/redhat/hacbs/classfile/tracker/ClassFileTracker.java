package com.redhat.hacbs.classfile.tracker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
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
        if (input.length < 4) {
            return input;
        }
        //check for the zip header
        if (input[3] != 0x04 ||
                input[2] != 0x03 ||
                input[1] != 0x4b ||
                input[0] != 0x50) {
            return input;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(input))) {
            try (ZipOutputStream zipOut = new ZipOutputStream(out)) {
                var entry = zipIn.getNextEntry();
                while (entry != null) {
                    if (entry.getName().endsWith(".class")) {
                        zipOut.putNextEntry(entry);
                        zipOut.write(addTrackingDataToClass(zipIn.readAllBytes(), data));
                    } else {
                        zipOut.putNextEntry(entry);
                        zipOut.write(zipIn.readAllBytes());
                    }
                    entry = zipIn.getNextEntry();
                }
            }
        }
        return out.toByteArray();
    }

    public static Set<TrackingData> readTrackingDataFromJar(byte[] input) throws IOException {
        Set<TrackingData> ret = new HashSet<>();
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(input))) {
            var entry = zipIn.getNextEntry();
            while (entry != null) {
                if (entry.getName().endsWith(".class")) {
                    ret.add(readTrackingInformationFromClass(zipIn.readAllBytes()));
                    ;
                }
                entry = zipIn.getNextEntry();
            }
        }
        return ret;
    }

}
