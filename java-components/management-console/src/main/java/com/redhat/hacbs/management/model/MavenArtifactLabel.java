package com.redhat.hacbs.management.model;

import jakarta.persistence.*;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "name_id", "artifact_id" }))
public class MavenArtifactLabel extends PanacheEntity {

    @ManyToOne(optional = false)
    @JoinColumn
    public ArtifactLabelName name;

    @ManyToOne(optional = false)
    @JoinColumn
    public MavenArtifact artifact;

    public static MavenArtifactLabel getOrCreate(MavenArtifact mavenArtifact, String label) {
        ArtifactLabelName name = ArtifactLabelName.getOrCreate(label);
        MavenArtifactLabel existing = MavenArtifactLabel
                .find("name=:name and artifact=:artifact", Parameters.with("name", name).and("artifact", mavenArtifact))
                .firstResult();
        if (existing == null) {
            existing = new MavenArtifactLabel();
            existing.artifact = mavenArtifact;
            existing.name = name;
            existing.persistAndFlush();
        }
        return existing;

    }
}
