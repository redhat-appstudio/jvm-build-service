package com.redhat.hacbs.management.internal.model;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

import com.redhat.hacbs.management.model.ArtifactIdentifier;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class VersionDiscoveryQueue extends PanacheEntity {

    @OneToOne(optional = false)
    @JoinColumn
    public ArtifactIdentifier artifactIdentifier;

    @Temporal(TemporalType.TIMESTAMP)
    public Instant lastRun;

}
