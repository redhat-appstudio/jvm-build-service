package com.redhat.hacbs.management.model;

import java.time.Instant;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class GithubActionsBuild extends PanacheEntity {

    public String commit;
    public String repository;
    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    public Instant creationTime;
    public long workflowRunId;

    public String prUrl;
    @JoinColumn
    @OneToOne(cascade = CascadeType.ALL)
    public DependencySet dependencySet;
    public boolean complete;

}
