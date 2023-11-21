package com.redhat.hacbs.management.internal.discovery;

import java.time.Instant;
import java.time.Period;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.redhat.hacbs.management.internal.model.VersionDiscoveryQueue;
import com.redhat.hacbs.management.model.ArtifactIdentifier;

import io.quarkus.logging.Log;

@ApplicationScoped
public class DiscoveryQueueManager {

    @Inject
    EntityManager entityManager;

    @Inject
    Event<VersionDiscoveryQueueAddedEvent> discoveryQueueAddedEvent;

    //    void importComplete(@Observes InitialKubeImportCompleteEvent importComplete) {
    //        populateDiscoveryQueue();
    //    }

    @Transactional
    public void populateDiscoveryQueue() {
        List<Object[]> results = entityManager
                .createQuery(
                        "select a,b from ArtifactIdentifier a left join VersionDiscoveryQueue b on b.artifactIdentifier=a")
                .getResultList();
        for (var i : results) {
            ArtifactIdentifier identifier = (ArtifactIdentifier) i[0];
            VersionDiscoveryQueue queue = (VersionDiscoveryQueue) i[1];

            if (queue == null) {
                Log.infof("creating version discovery entry for %s:%s", identifier.group, identifier.artifact);
                queue = new VersionDiscoveryQueue();
                queue.artifactIdentifier = identifier;
                queue.persist();
            } else if (queue.lastRun.isBefore(Instant.now().minus(Period.ofWeeks(1)))) { //TODO: hard coded
                queue.lastRun = null;
                Log.infof("retrying old version discovery for %s:%s", identifier.group, identifier.artifact);
            }
        }
        discoveryQueueAddedEvent.fireAsync(new VersionDiscoveryQueueAddedEvent());
    }
}
