package com.redhat.hacbs.management.model;

import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class ShadingDetails extends PanacheEntity {

    @ManyToOne
    @JoinColumn(nullable = false)
    @JsonIgnore
    public StoredDependencyBuild storedDependencyBuild;

    @JoinColumn(nullable = false)
    @ManyToOne
    public MavenArtifact contaminant;
    @ManyToMany
    @JoinTable
    public List<MavenArtifact> contaminatedArtifacts;

    public String buildId;
    public String source;
    public boolean allowed;
    public boolean rebuildAvailable;
}
