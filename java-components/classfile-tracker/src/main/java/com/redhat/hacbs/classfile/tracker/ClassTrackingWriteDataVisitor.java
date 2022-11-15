package com.redhat.hacbs.classfile.tracker;

import java.util.Objects;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;

class ClassTrackingWriteDataVisitor extends ClassVisitor {

    final TrackingData contents;
    boolean existing = false;

    public ClassTrackingWriteDataVisitor(int api, TrackingData contents) {
        super(api);
        this.contents = contents;
    }

    public ClassTrackingWriteDataVisitor(int api, ClassVisitor classVisitor, TrackingData contents) {
        super(api, classVisitor);
        this.contents = contents;
    }

    @Override
    public void visitAttribute(Attribute attribute) {
        if (Objects.equals(attribute.type, ClassFileSourceAttribute.ATTRIBUTE_NAME)) {
            existing = true;
        }
        super.visitAttribute(attribute);
    }

    @Override
    public void visitEnd() {
        if (!existing) {
            visitAttribute(new ClassFileSourceAttribute(contents));
        }
        super.visitEnd();
    }

}
