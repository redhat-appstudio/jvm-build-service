package com.redhat.hacbs.management.model;

import java.util.List;
import java.util.Map;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.JoinColumn;
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
    @JoinColumn(nullable = false)
    public MavenArtifact mavenArtifact;

    public static void create(String gav, boolean priority, Map<String, String> labels) {
        MavenArtifact mavenArtifact = MavenArtifact.forGav(gav);
        create(mavenArtifact, priority);
        for (var i : labels.entrySet()) {
            MavenArtifactLabel.getOrCreate(mavenArtifact, i.getKey(), i.getValue());
        }
    }

    public static void rebuild(String gav, boolean priority, Map<String, String> labels) {
        MavenArtifact mavenArtifact = MavenArtifact.forGav(gav);
        create(mavenArtifact, priority, true);
        for (var i : labels.entrySet()) {
            MavenArtifactLabel.getOrCreate(mavenArtifact, i.getKey(), i.getValue());
        }
    }

    public static void rebuild(MavenArtifact mavenArtifact, boolean priority, Map<String, String> labels) {
        create(mavenArtifact, priority, true);
        for (var i : labels.entrySet()) {
            MavenArtifactLabel.getOrCreate(mavenArtifact, i.getKey(), i.getValue());
        }
    }

    public static void create(MavenArtifact mavenArtifact, boolean priority) {
        create(mavenArtifact, false, false);
    }

    public static void create(MavenArtifact mavenArtifact, boolean priority, boolean rebuild) {
        BuildQueue existing = BuildQueue.find("mavenArtifact", mavenArtifact).firstResult();
        if (existing == null) {
            EntityManager entityManager = Arc.container().instance(EntityManager.class).get();
            List<StoredDependencyBuild> existingBuild = entityManager
                    .createQuery(
                            "select a from StoredDependencyBuild a join a.buildAttempts ba join ba.producedArtifacts s where s=:artifact")
                    .setParameter("artifact", mavenArtifact)
                    .getResultList();
            if (existingBuild.isEmpty() || rebuild) {
                BuildQueue queue = new BuildQueue();
                queue.mavenArtifact = mavenArtifact;
                queue.priority = priority;
                queue.rebuild = rebuild;
                queue.persistAndFlush();
            }
        } else if (priority || rebuild) {
            existing.priority |= priority;
            existing.rebuild |= rebuild;
        }
    }

    public static boolean inBuildQueue(MavenArtifact mavenArtifact) {
        return BuildQueue.count("mavenArtifact", mavenArtifact) > 0;
    }
}
