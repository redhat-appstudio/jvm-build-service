package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;

@Entity()
@Table(uniqueConstraints = @UniqueConstraint(name = "mavengavunique", columnNames = { "version", "identifier_id" }))
public class MavenArtifact extends PanacheEntity {

    @ManyToOne
    public ArtifactIdentifier identifier;

    @Column(nullable = false)
    public String version;

    public String group() {
        return identifier.group;
    }

    public String artifact() {
        return identifier.artifact;
    }

    public String version() {
        return version;
    }

    public String gav() {
        return group() + ":" + artifact() + ":" + version;
    }

    public static MavenArtifact forGav(String gav) {
        var parts = gav.split(":");
        var group = parts[0];
        var artifact = parts[1];
        var version = parts[2];
        ArtifactIdentifier artifactIdentifier = ArtifactIdentifier.findORCreate(group, artifact);
        MavenArtifact ret = find("identifier = :identifier and version = :version",
                Parameters.with("identifier", artifactIdentifier).and("version", version)).firstResult();
        if (ret == null) {
            ret = new MavenArtifact();
            ret.identifier = artifactIdentifier;
            ret.version = version;
            ret.persist();
        }
        return ret;
    }

}
