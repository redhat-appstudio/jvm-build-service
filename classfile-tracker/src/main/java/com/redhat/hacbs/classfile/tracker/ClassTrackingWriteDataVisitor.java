package com.redhat.hacbs.classfile.tracker;

import org.objectweb.asm.ClassVisitor;

class ClassTrackingWriteDataVisitor extends ClassVisitor {

    final TrackingData contents;

    public ClassTrackingWriteDataVisitor(int api, TrackingData contents) {
        super(api);
        this.contents = contents;
    }

    public ClassTrackingWriteDataVisitor(int api, ClassVisitor classVisitor, TrackingData contents) {
        super(api, classVisitor);
        this.contents = contents;
    }

    @Override
    public void visitEnd() {
        visitAttribute(new ClassFileSourceAttribute(contents));
        super.visitEnd();
    }

}
