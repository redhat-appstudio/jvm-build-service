package com.redhat.hacbs.management.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.panache.common.Parameters;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "repository_id", "tag", "hash", "contextPath" }))
public class BuildIdentifier extends PanacheEntity {

    @ManyToOne(optional = false)
    public ScmRepository repository;

    @Column(nullable = false)
    public String tag;

    @Column(nullable = false)
    public String hash;

    @Column(nullable = false)
    public String contextPath;

    @Column(nullable = false, unique = true)
    public String dependencyBuildName;

    public static BuildIdentifier findORCreate(String repoUrl, String tag, String hash, String contextPath,
            String dependencyBuildName) {
        ScmRepository scmRepository = ScmRepository.findORCreate(repoUrl);
        if (contextPath == null) {
            contextPath = "";
        }
        BuildIdentifier ret = find(
                "dependencyBuildName=:dependencyBuildName", Parameters.with("dependencyBuildName", dependencyBuildName))
                .firstResult();
        if (ret == null) {
            ret = new BuildIdentifier();
            ret.repository = scmRepository;
            ret.tag = tag;
            ret.hash = hash;
            ret.contextPath = contextPath;
            ret.dependencyBuildName = dependencyBuildName;
            ret.persistAndFlush();
        }
        return ret;
    }

}
