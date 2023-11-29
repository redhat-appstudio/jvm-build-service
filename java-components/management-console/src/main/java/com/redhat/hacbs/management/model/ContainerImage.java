package com.redhat.hacbs.management.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;

@Entity()
@Table(uniqueConstraints = { @UniqueConstraint(columnNames = { "image", "digest" }) })
public class ContainerImage extends PanacheEntity {

    @Column(nullable = false, length = -1)
    public String image;

    public String tag;
    @Column(nullable = false)
    public String digest;

    @JoinColumn
    @OneToOne(cascade = CascadeType.ALL)
    public DependencySet dependencySet;

    public boolean analysisComplete;
    public boolean analysisFailed;

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
        ContainerImage containerImage = find("digest=:digest and image=:image",
                Parameters.with("digest", digest).and("image", imagePart))
                .firstResult();
        return containerImage;
    }

    public static ContainerImage getOrCreate(String image) {
        int index = image.indexOf("@");
        String digest = image.substring(index + 1);
        String imagePart = image.substring(0, index);
        String tag = "";
        int tagIndex = imagePart.indexOf(":");
        if (tagIndex > 0) {
            tag = image.substring(tagIndex);
            imagePart = imagePart.substring(0, tagIndex);
        }
        ContainerImage containerImage = find("digest=:digest and image=:image",
                Parameters.with("digest", digest).and("image", imagePart))
                .firstResult();
        if (containerImage == null) {
            containerImage = new ContainerImage();
            containerImage.dependencySet = new DependencySet();
            containerImage.tag = tag;
            containerImage.image = imagePart;
            containerImage.digest = digest;
            containerImage.dependencySet.identifier = image;
            containerImage.dependencySet.type = "container-image";
            containerImage.persistAndFlush();
        }
        return containerImage;
    }
}
