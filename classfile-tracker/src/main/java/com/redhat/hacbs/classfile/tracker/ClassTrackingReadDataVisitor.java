package com.redhat.hacbs.classfile.tracker;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;

class ClassTrackingReadDataVisitor extends ClassVisitor {

    private TrackingData contents;

    public ClassTrackingReadDataVisitor(int api) {
        super(api);
    }

    public ClassTrackingReadDataVisitor(int api, ClassVisitor classVisitor) {
        super(api, classVisitor);
    }

    public TrackingData getContents() {
        return contents;
    }

    @Override
    public void visitAttribute(Attribute attribute) {
        super.visitAttribute(attribute);
        if (attribute instanceof ClassFileSourceAttribute) {
            contents = ((ClassFileSourceAttribute) attribute).contents;
        }
    }
}
