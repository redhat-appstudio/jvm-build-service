package com.redhat.hacbs.management.model;

import jakarta.persistence.*;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "name_id", "artifact_id", "value" }))
public class MavenArtifactLabel extends PanacheEntity {

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    public ArtifactLabelName name;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    public MavenArtifact artifact;

    public String value;

    public static MavenArtifactLabel getOrCreate(MavenArtifact mavenArtifact, String label) {
        return getOrCreate(mavenArtifact, label, null);
    }

    public static MavenArtifactLabel getOrCreate(MavenArtifact mavenArtifact, String label, String value) {
        ArtifactLabelName name = ArtifactLabelName.getOrCreate(label);
        MavenArtifactLabel existing = MavenArtifactLabel
                .find("name=:name and artifact=:artifact and value=:value",
                        Parameters.with("name", name).and("artifact", mavenArtifact).and("value", value))
                .firstResult();
        if (existing == null) {
            existing = new MavenArtifactLabel();
            existing.artifact = mavenArtifact;
            existing.name = name;
            existing.value = value;
            existing.persistAndFlush();
        }
        return existing;

    }
}
