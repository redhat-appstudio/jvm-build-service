package com.redhat.hacbs.classfile.tracker;

import java.util.Objects;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;

class ClassTrackingWriteDataVisitor extends ClassVisitor {

    final TrackingData contents;
    boolean existing = false;
    final boolean overwrite;

    public ClassTrackingWriteDataVisitor(int api, TrackingData contents, boolean overwrite) {
        super(api);
        this.contents = contents;
        this.overwrite = overwrite;
    }

    public ClassTrackingWriteDataVisitor(int api, ClassVisitor classVisitor, TrackingData contents, boolean overwrite) {
        super(api, classVisitor);
        this.contents = contents;
        this.overwrite = overwrite;
    }

    @Override
    public void visitAttribute(Attribute attribute) {
        if (Objects.equals(attribute.type, ClassFileSourceAttribute.ATTRIBUTE_NAME)) {
            if (overwrite) {
                return;
            }
            existing = true;
        }
        super.visitAttribute(attribute);
    }

    @Override
    public void visitEnd() {
        if (!existing) {
            super.visitAttribute(new ClassFileSourceAttribute(contents));
        }
        super.visitEnd();
    }

}
