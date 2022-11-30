package com.redhat.hacbs.classfile.tracker;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;

class ClassTrackingReadDataVisitor extends ClassVisitor {

    private TrackingData contents;
    private String className;

    public ClassTrackingReadDataVisitor(int api) {
        super(api);
    }

    public ClassTrackingReadDataVisitor(int api, ClassVisitor classVisitor) {
        super(api, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        this.className = name;
    }

    public TrackingData getContents() {
        return contents;
    }

    public String getClassName() {
        return className;
    }

    @Override
    public void visitAttribute(Attribute attribute) {
        super.visitAttribute(attribute);
        if (attribute instanceof ClassFileSourceAttribute) {
            contents = ((ClassFileSourceAttribute) attribute).contents;
        }
    }
}
