package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;

@Entity
public class ArtifactLabelName extends PanacheEntity {

    @Column(unique = true)
    public String name;

    public static ArtifactLabelName getOrCreate(String name) {
        ArtifactLabelName artifactLabelName = find("name=:name",
                Parameters.with("name", name))
                .firstResult();
        if (artifactLabelName == null) {
            artifactLabelName = new ArtifactLabelName();
            artifactLabelName.name = name;
            artifactLabelName.persistAndFlush();
        }
        return artifactLabelName;
    }

    public static ArtifactLabelName get(String name) {
        return find("name=:name",
                Parameters.with("name", name))
                .firstResult();
    }
}
