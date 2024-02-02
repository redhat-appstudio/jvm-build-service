package com.redhat.hacbs.management.model;

import java.time.Instant;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;

@Entity()
@Table(uniqueConstraints = { @UniqueConstraint(columnNames = { "repository_id", "digest" }) })
public class ContainerImage extends PanacheEntity {

    @JoinColumn(nullable = false)
    @ManyToOne
    public ContainerImageRepository repository;

    public String tag;
    @Column(nullable = false)
    public String digest;

    @JoinColumn
    @OneToOne(cascade = CascadeType.ALL)
    public DependencySet dependencySet;

    public boolean analysisComplete;
    public boolean analysisFailed;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false)
    public Instant timestamp;

    public static ContainerImage findImage(String image) {
        int index = image.indexOf("@");
        if (index < 0) {
            return null;
        }
        String digest = image.substring(index + 1);
        String imagePart = image.substring(0, index);
        int tagIndex = imagePart.indexOf(":");
        if (tagIndex > 0) {
            imagePart = imagePart.substring(0, tagIndex);
        }
        ContainerImageRepository repo = ContainerImageRepository.findByRepository(imagePart);
        ContainerImage containerImage = find("digest=:digest and repository=:repository",
                Parameters.with("digest", digest).and("repository", repo))
                .firstResult();
        return containerImage;
    }

    public static ContainerImage getOrCreate(String image, Instant timestamp) {
        int index = image.indexOf("@");
        String digest = image.substring(index + 1);
        String imagePart = image.substring(0, index);
        String tag = "";
        int tagIndex = imagePart.indexOf(":");
        if (tagIndex > 0) {
            tag = imagePart.substring(tagIndex + 1);
            imagePart = imagePart.substring(0, tagIndex);
        }
        ContainerImageRepository repo = ContainerImageRepository.getOrCreate(imagePart);
        ContainerImage containerImage = find("digest=:digest and repository=:repository",
                Parameters.with("digest", digest).and("repository", repo))
                .firstResult();
        if (containerImage == null) {
            containerImage = new ContainerImage();
            containerImage.dependencySet = new DependencySet();
            containerImage.tag = tag;
            containerImage.repository = repo;
            containerImage.digest = digest;
            containerImage.timestamp = timestamp; //we can't get the exact image age
            containerImage.dependencySet.identifier = image;
            containerImage.dependencySet.type = "container-image";
            containerImage.persistAndFlush();
        }
        return containerImage;
    }

    @Transient
    public String getFullName() {
        return repository + (tag != null ? ":" + tag : "") + (digest != null ? "@" + digest : "");
    }
}
