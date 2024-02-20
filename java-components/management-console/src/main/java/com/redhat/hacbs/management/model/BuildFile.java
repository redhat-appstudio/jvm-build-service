package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class BuildFile extends PanacheEntity {

    @Enumerated(EnumType.ORDINAL)
    public S3FileType type;

    @Column(nullable = false)
    public String uri; //the s3 URI

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    BuildAttempt build;
}
