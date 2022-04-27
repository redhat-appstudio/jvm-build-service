package com.redhat.hacbs.classfile.tracker;

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
        byteVector.putShort(this.contents.gav == null ? 0 : classWriter.newUTF8(this.contents.gav));
        byteVector.putShort(this.contents.source == null ? 0 : classWriter.newUTF8(this.contents.source));
        return byteVector;
    }

    @Override
    protected Attribute read(ClassReader classReader, int offset, int length, char[] charBuffer, int codeAttributeOffset,
            Label[] labels) {
        String gav = classReader.readUTF8(offset, charBuffer);
        String source = classReader.readUTF8(offset + 2, charBuffer);
        return new ClassFileSourceAttribute(new TrackingData(gav, source));
    }
}
