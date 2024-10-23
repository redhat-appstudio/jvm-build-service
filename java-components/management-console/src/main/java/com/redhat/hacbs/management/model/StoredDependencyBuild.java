package com.redhat.hacbs.management.model;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NoResultException;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "buildidentifier_id" }))
public class StoredDependencyBuild extends PanacheEntity {

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    public BuildIdentifier buildIdentifier;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(nullable = false)
    public Instant creationTimestamp;

    public String version;

    public boolean succeeded;

    public boolean contaminated;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "dependencyBuild")
    @OrderBy("startTime desc")
    public List<BuildAttempt> buildAttempts;

    public static StoredDependencyBuild findByArtifact(MavenArtifact mavenArtifact) {
        try {
            return (StoredDependencyBuild) Arc.container().instance(EntityManager.class).get().createQuery(
                    "select b from StoredArtifactBuild s inner join StoredDependencyBuild b on b.buildIdentifier=s.buildIdentifier where s.mavenArtifact = :artifact order by b.creationTimestamp desc")
                    .setParameter("artifact", mavenArtifact)
                    .setMaxResults(1)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}
