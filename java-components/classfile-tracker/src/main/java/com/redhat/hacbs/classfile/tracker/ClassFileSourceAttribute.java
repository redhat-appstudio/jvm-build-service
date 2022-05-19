package com.redhat.hacbs.classfile.tracker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;

class ClassFileSourceAttribute extends Attribute {

    public static final String ATTRIBUTE_NAME = "com.redhat.hacbs.ClassFileSource";

    TrackingData contents;

    public ClassFileSourceAttribute(TrackingData contents) {
        super(ATTRIBUTE_NAME);
        this.contents = contents;
    }

    @Override
    protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
        ByteVector byteVector = new ByteVector();
        writeString(byteVector, this.contents.gav);
        writeString(byteVector, this.contents.source);
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
    protected Attribute read(ClassReader classReader, int offset, int length, char[] charBuffer, int codeAttributeOffset,
            Label[] labels) {
        String gav = null;
        String source = null;
        int gavLength = classReader.readInt(offset);
        offset += 4;
        if (gavLength > 0) {
            ByteArrayOutputStream ba = new ByteArrayOutputStream();
            for (var i = 0; i < gavLength; ++i) {
                ba.write(classReader.readByte(offset++));
            }
            gav = ba.toString(StandardCharsets.UTF_8);
        }
        int sourceLength = classReader.readInt(offset);
        offset += 4;
        if (sourceLength > 0) {
            ByteArrayOutputStream ba = new ByteArrayOutputStream();
            for (var i = 0; i < sourceLength; ++i) {
                ba.write(classReader.readByte(offset++));
            }
            source = ba.toString(StandardCharsets.UTF_8);
        }
        return new ClassFileSourceAttribute(new TrackingData(gav, source));
    }
}
