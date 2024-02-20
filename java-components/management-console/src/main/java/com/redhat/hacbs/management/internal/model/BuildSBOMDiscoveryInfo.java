package com.redhat.hacbs.management.internal.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;

import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.DependencySet;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class BuildSBOMDiscoveryInfo extends PanacheEntity {

    @OneToOne(optional = false)
    @JoinColumn
    public BuildAttempt build;

    public boolean succeeded;

    @JoinColumn(nullable = true)
    @OneToOne(optional = true, cascade = CascadeType.ALL)
    public DependencySet dependencySet;
}
