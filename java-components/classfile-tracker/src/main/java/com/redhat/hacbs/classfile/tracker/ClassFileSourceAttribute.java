package com.redhat.hacbs.classfile.tracker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;

class ClassFileSourceAttribute extends Attribute {
    public static final int VERSION = 1;

    public static final String ATTRIBUTE_NAME = "com.redhat.hacbs.ClassFileSource";

    TrackingData contents;

    public ClassFileSourceAttribute(TrackingData contents) {
        super(ATTRIBUTE_NAME);
        this.contents = contents;
    }

    @Override
    protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
        ByteVector byteVector = new ByteVector();
        byteVector.putByte(VERSION);
        writeString(byteVector, this.contents.gav);
        writeString(byteVector, this.contents.source);
        byteVector.putInt(contents.getAttributes().size());
        for (var e : contents.getAttributes().entrySet()) {
            writeString(byteVector, e.getKey());
            writeString(byteVector, e.getValue());
        }
        return byteVector;
    }

    private void writeString(ByteVector byteVector, String data) {
        if (data == null) {
            byteVector.putInt(0);
        } else {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            byteVector.putInt(bytes.length);
            for (var c : bytes) {
                byteVector.putByte(c);
            }
        }
    }

    @Override
    protected Attribute read(ClassReader classReader, int startOffset, int length, char[] charBuffer, int codeAttributeOffset,
            Label[] labels) {
        AtomicInteger offset = new AtomicInteger(startOffset);
        int version = classReader.readByte(offset.getAndIncrement());
        if (version != VERSION) {
            throw new RuntimeException("Unknown version " + version);
        }
        String gav = readString(classReader, offset);
        String source = readString(classReader, offset);
        Map<String, String> attributes = new HashMap<>();
        int attributeCount = classReader.readInt(offset.getAndAdd(4));
        for (int i = 0; i < attributeCount; ++i) {
            String key = readString(classReader, offset);
            String val = readString(classReader, offset);
            attributes.put(key, val);
        }
        return new ClassFileSourceAttribute(new TrackingData(gav, source, attributes));
    }

    private String readString(ClassReader classReader, AtomicInteger offset) {
        String source = null;
        int sourceLength = classReader.readInt(offset.getAndAdd(4));
        if (sourceLength > 0) {
            ByteArrayOutputStream ba = new ByteArrayOutputStream();
            for (var i = 0; i < sourceLength; ++i) {
                ba.write(classReader.readByte(offset.getAndIncrement()));
            }
            source = ba.toString(StandardCharsets.UTF_8);
        }
        return source;
    }
}
