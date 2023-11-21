package com.redhat.hacbs.management.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "mavenartifact_id", "uid" }))
public class StoredArtifactBuild extends PanacheEntity {

    @ManyToOne
    @JoinColumn(nullable = false)
    public MavenArtifact mavenArtifact;

    @Column(nullable = false)
    public String uid;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    public Instant creationTimestamp;

    @Column(nullable = false)
    public String status;

    @Column(length = -1)
    public String message;

    @ManyToOne
    @JoinColumn
    public BuildIdentifier buildIdentifier;

}
