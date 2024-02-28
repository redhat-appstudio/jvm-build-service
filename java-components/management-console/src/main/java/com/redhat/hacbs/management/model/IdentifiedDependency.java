package com.redhat.hacbs.management.model;

import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class IdentifiedDependency extends PanacheEntity {

    @ManyToOne
    @JoinColumn(nullable = false)
    public DependencySet dependencySet;

    @ManyToOne(cascade = CascadeType.PERSIST)
    @JoinColumn(nullable = false)
    public MavenArtifact mavenArtifact;

    public String buildId;

    public String source;

    public String attributes;

    public String shadedInto;

    public boolean buildComplete;
    public boolean buildSuccessful;

    public boolean isTrusted() {
        //TODO: better definition of trust
        return (buildId != null && !buildId.isEmpty()) || Objects.equals(source, "redhat") || Objects.equals(source, "rebuilt");
    }
}
