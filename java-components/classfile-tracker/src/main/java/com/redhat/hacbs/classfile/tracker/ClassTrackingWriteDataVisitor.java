package com.redhat.hacbs.classfile.tracker;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;

class ClassTrackingWriteDataVisitor extends ClassVisitor {

    public static final String SHADED_INTO = "shaded-into";
    final TrackingData contents;
    ClassFileSourceAttribute existing = null;
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
            existing = (ClassFileSourceAttribute) attribute;
        }
        super.visitAttribute(attribute);
    }

    @Override
    public void visitEnd() {
        if (existing == null) {
            super.visitAttribute(new ClassFileSourceAttribute(contents));
        } else if (!existing.contents.gav.equals(contents.gav)) {
            Map<String, String> attributes = new HashMap<>(existing.contents.getAttributes());
            if (existing.contents.getAttributes().containsKey(SHADED_INTO)) {
                String existing = this.existing.contents.getAttributes().get(SHADED_INTO);
                var found = false;
                for (var part : existing.split(",")) {
                    if (part.equals(contents.gav)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    attributes.put(SHADED_INTO, contents.gav + "," + existing);
                }
            } else {
                attributes.put(SHADED_INTO, contents.gav);
            }
            super.visitAttribute(new ClassFileSourceAttribute(
                    new TrackingData(existing.contents.gav, existing.contents.source, attributes)));
        }
        super.visitEnd();
    }

}
