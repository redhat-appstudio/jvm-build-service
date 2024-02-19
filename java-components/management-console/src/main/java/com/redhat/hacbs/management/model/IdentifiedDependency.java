package com.redhat.hacbs.management.model;

import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class IdentifiedDependency extends PanacheEntity {

    @ManyToOne
    public DependencySet dependencySet;

    @ManyToOne(cascade = CascadeType.PERSIST)
    public MavenArtifact mavenArtifact;

    public String buildId;

    public String source;

    public String attributes;

    public boolean buildComplete;
    public boolean buildSuccessful;

    public boolean isTrusted() {
        //TODO: better definition of trust
        return (buildId != null && !buildId.isEmpty()) || Objects.equals(source, "redhat") || Objects.equals(source, "rebuilt");
    }
}
