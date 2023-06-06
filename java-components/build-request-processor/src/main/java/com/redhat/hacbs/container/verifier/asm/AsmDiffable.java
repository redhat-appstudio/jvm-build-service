package com.redhat.hacbs.container.verifier.asm;

import static org.apache.commons.lang3.builder.ToStringStyle.MULTI_LINE_STYLE;

import org.apache.commons.lang3.builder.DiffResult;
import org.apache.commons.lang3.builder.Diffable;
import org.apache.commons.lang3.builder.ReflectionDiffBuilder;

public interface AsmDiffable<T> extends Diffable<T> {
    @Override
    @SuppressWarnings("unchecked")
    default DiffResult<T> diff(T obj) {
        return new ReflectionDiffBuilder<>((T) this, obj, MULTI_LINE_STYLE).build();
    }

    default String getName() {
        return "";
    }
}
