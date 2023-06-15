package com.redhat.hacbs.container.analyser.dependencies;

import java.util.*;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.redhat.hacbs.container.results.ResultsUpdater;

import io.quarkus.logging.Log;

@Singleton
public class RebuildService {

    @Inject
    Instance<ResultsUpdater> resultsUpdater;

    public void rebuild(String taskRunName, Set<String> gavs) {
        Log.infof("Identified %s Community Dependencies: %s", gavs.size(), new TreeSet<>(gavs));
        resultsUpdater.get().updateResults(taskRunName, Map.of("JAVA_COMMUNITY_DEPENDENCIES", String.join(",", gavs)));
    }
}
