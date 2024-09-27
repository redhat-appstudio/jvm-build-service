package com.redhat.hacbs.domainproxy;

import java.util.Comparator;

import com.redhat.hacbs.common.maven.GAV;

public record Dependency(GAV GAV, String classifier) implements Comparable<Dependency> {
    @Override
    public int compareTo(Dependency o) {
        return Comparator.comparing(Dependency::GAV)
                .thenComparing(Dependency::classifier, Comparator.nullsFirst(Comparator.naturalOrder()))
                .compare(this, o);
    }
}
