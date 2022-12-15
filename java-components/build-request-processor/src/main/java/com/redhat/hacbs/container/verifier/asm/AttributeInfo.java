package com.redhat.hacbs.container.verifier.asm;

import org.objectweb.asm.Attribute;

public record AttributeInfo(String type) implements AsmDiffable<AttributeInfo> {
    public AttributeInfo(Attribute attribute) {
        this(attribute.type);
    }
}
