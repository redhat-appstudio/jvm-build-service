package com.redhat.hacbs.management.internal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;

import com.redhat.hacbs.management.model.StoredDependencyBuild;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class BuildSBOMDiscoveryInfo extends PanacheEntity {

    @OneToOne(optional = false)
    @JoinColumn
    public StoredDependencyBuild build;

    public boolean succeeded;

    @Column(length = -1)
    public String discoveredGavs;

}
