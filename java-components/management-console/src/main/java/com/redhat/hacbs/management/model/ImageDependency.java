package com.redhat.hacbs.management.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class ImageDependency extends PanacheEntity {

    @ManyToOne
    public ContainerImage image;

    @ManyToOne(cascade = CascadeType.PERSIST)
    public MavenArtifact mavenArtifact;

    public String buildId;

    public String source;

    public String attributes;
}
