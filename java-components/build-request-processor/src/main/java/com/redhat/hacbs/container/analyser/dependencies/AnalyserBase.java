package com.redhat.hacbs.container.analyser.dependencies;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;

import io.quarkus.logging.Log;
import picocli.CommandLine;

public abstract class AnalyserBase implements Runnable {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @CommandLine.Option(names = "--task-run-name")
    String taskRunName;

    @CommandLine.Option(names = "--allowed-sources", defaultValue = "redhat,rebuilt", split = ",")
    Set<String> allowedSources;

    @Inject
    RebuildService rebuild;

    @CommandLine.Option(names = "-c")
    Path dependenciesCount;

    @CommandLine.Option(names = "-u")
    Path untrustedDependenciesCount;

    @CommandLine.Option(names = "--publishers")
    Path publishers;

    protected String imageDigest = "";

    @Override
    public void run() {
        try {
            Set<String> gavs = new HashSet<>();
            Set<TrackingData> trackingData = new HashSet<>();
            doAnalysis(gavs, trackingData);
            rebuild.rebuild(taskRunName, gavs);
            writeResults(gavs, trackingData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    abstract void doAnalysis(Set<String> gavs, Set<TrackingData> trackingData) throws Exception;

    void writeResults(Set<String> gavs, Set<TrackingData> trackingData) throws IOException {
        if (dependenciesCount != null) {
            Files.writeString(dependenciesCount, trackingData.size() + "");
        }
        if (untrustedDependenciesCount != null) {
            Files.writeString(untrustedDependenciesCount, gavs.size() + "");
        }
        if (publishers != null) {
            Map<String, Integer> pm = new HashMap<>();
            for (var i : trackingData) {
                Integer existing = pm.get(i.source);
                if (existing == null) {
                    pm.put(i.source, 1);
                } else {
                    pm.put(i.source, existing + 1);
                }
            }
            MAPPER.writer().writeValue(publishers.toFile(), pm);
        }
    }

    FileVisitResult handleFile(String fileName, InputStream contents, Set<TrackingData> trackingData, Set<String> gavs)
            throws IOException {
        Log.debugf("Processing %s", fileName);
        var jarData = ClassFileTracker.readTrackingDataFromFile(contents, fileName);
        trackingData.addAll(jarData);
        for (var data : jarData) {
            if (data != null) {
                Log.infof("found %s", data.gav);
                if (!allowedSources.contains(data.source)) {
                    Log.debugf("Found GAV %s in %s", data.gav, fileName);
                    gavs.add(data.gav);
                }
            }
        }
        return FileVisitResult.CONTINUE;
    }

}
