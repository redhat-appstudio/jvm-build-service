package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class AdditionalDownload extends PanacheEntity {
    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    public BuildAttempt buildAttempt;
    @Column(length = -1)
    public String uri;
    public String sha256;
    public String fileName;
    public String binaryPath;
    public String packageName;
    public String fileType;
}
