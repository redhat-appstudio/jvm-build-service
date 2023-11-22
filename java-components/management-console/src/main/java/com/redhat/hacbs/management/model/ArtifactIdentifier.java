package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;

@Entity
public class ArtifactIdentifier extends PanacheEntity {

    @Column(name = "maven_group", length = -1)
    public String group;
    @Column(name = "maven_artifact", length = -1)
    public String artifact;

    public static ArtifactIdentifier findORCreate(String group, String artifact) {
        ArtifactIdentifier ret = find("group = :group and artifact = :artifact",
                Parameters.with("group", group).and("artifact", artifact)).firstResult();
        if (ret == null) {
            ret = new ArtifactIdentifier();
            ret.artifact = artifact;
            ret.group = group;
            ret.persistAndFlush();
        }
        return ret;
    }
}
