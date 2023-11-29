package com.redhat.hacbs.management.model;

import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ManyToOne;

import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class BuildQueue extends PanacheEntity {

    /**
     * If this is true then it is a priority build
     */
    public boolean priority;

    /**
     * existing builds are ignored unless this is true
     */
    public boolean rebuild;

    @ManyToOne
    public MavenArtifact mavenArtifact;

    public static void create(String gav, boolean priority, String... labels) {
        MavenArtifact mavenArtifact = MavenArtifact.forGav(gav);
        create(mavenArtifact, priority);
        for (var i : labels) {
            MavenArtifactLabel.getOrCreate(mavenArtifact, i);
        }
    }

    public static void create(MavenArtifact mavenArtifact, boolean priority) {
        BuildQueue existing = BuildQueue.find("mavenArtifact", mavenArtifact).firstResult();
        if (existing == null) {
            EntityManager entityManager = Arc.container().instance(EntityManager.class).get();
            List<StoredDependencyBuild> existingBuild = entityManager
                    .createQuery(
                            "select a from StoredDependencyBuild a join a.producedArtifacts s where s=:artifact")
                    .setParameter("artifact", mavenArtifact)
                    .getResultList();
            if (existingBuild.isEmpty()) {
                BuildQueue queue = new BuildQueue();
                queue.mavenArtifact = mavenArtifact;
                queue.priority = priority;
                queue.persistAndFlush();
            }
        } else if (priority) {
            existing.priority = true;
        }
    }
}
