package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class ShadingDetails extends PanacheEntity {

    @Column(nullable = false)
    public String artifact;

    @Column(nullable = false)
    public String shadedDependencies;

}
