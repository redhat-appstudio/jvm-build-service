package com.redhat.hacbs.container.analyser.dependencies;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.cyclonedx.BomGeneratorFactory;
import org.cyclonedx.CycloneDxSchema;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;
import com.redhat.hacbs.container.results.ResultsUpdater;

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

    @CommandLine.Option(names = "-s")
    Path sbom;

    @CommandLine.Option(names = "-c")
    Path dependenciesCount;

    @CommandLine.Option(names = "-u")
    Path untrustedDependenciesCount;

    @CommandLine.Option(names = "--publishers")
    Path publishers;

    /**
     * special mode for dealing with JvmImageScan pipelines
     * in this mode we want to merge the SBom with the tracking data
     * and stick it all in a result
     */
    @CommandLine.Option(names = "--output-all-dependencies")
    boolean outputAllDependencies;

    @Inject
    Instance<ResultsUpdater> resultsUpdater;

    protected String imageDigest = "";

    @Override
    public void run() {
        try {
            Set<String> gavs = new HashSet<>();
            Set<TrackingData> trackingData = new HashSet<>();
            doAnalysis(gavs, trackingData);
            if (!outputAllDependencies) {
                rebuild.rebuild(taskRunName, gavs);
                writeResults(gavs, trackingData);
                writeSbom(trackingData);
            } else {
                handleAllDependencies(trackingData);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void handleAllDependencies(Set<TrackingData> trackingData) throws Exception {
        //TODO: this is a bit of a hack at the moment
        //its mostly just here to support a demo for now
        Bom bom;
        InputStream existing = null;
        try {
            if (Files.exists(sbom)) {
                existing = Files.newInputStream(sbom);
            }
            bom = SBomGenerator.generateSBom(trackingData, existing);
        } finally {
            if (existing != null) {
                existing.close();
            }
        }
        StringBuilder result = new StringBuilder();

        for (var it = bom.getComponents().iterator(); it.hasNext();) {
            var i = it.next();
            if (i.getPurl() != null && i.getPurl().startsWith("pkg:maven")) {
                //TODO: syft is pretty bad, it misses gradle dependencies group name
                if (i.getGroup() == null) {
                    continue;
                }
                if (!result.isEmpty()) {
                    result.append(",");
                }
                result.append(i.getGroup()).append(":").append(i.getName()).append(":").append(i.getVersion()).append(";")
                        .append(i.getPublisher());
                if (i.getProperties() != null) {
                    for (var attr : i.getProperties()) {
                        if (attr.getName().startsWith("java:")) {
                            result.append(";");
                            result.append(attr.getName().substring(5)).append("=").append(attr.getValue());
                        }
                    }
                }
            }
        }
        resultsUpdater.get().updateResults(taskRunName,
                Map.of("JVM_DEPENDENCIES", result.toString(), "IMAGE_DIGEST", imageDigest));
    }

    abstract void doAnalysis(Set<String> gavs, Set<TrackingData> trackingData) throws Exception;

    void writeSbom(Set<TrackingData> trackingData) throws IOException {
        Bom bom;
        InputStream existing = null;
        try {
            if (Files.exists(sbom)) {
                existing = Files.newInputStream(sbom);
            }
            bom = SBomGenerator.generateSBom(trackingData, existing);
        } finally {
            if (existing != null) {
                existing.close();
            }
        }

        BomJsonGenerator generator = BomGeneratorFactory.createJson(CycloneDxSchema.Version.VERSION_14, bom);
        String sbom = generator.toJsonString();
        Log.infof("Generated SBOM:\n%s", sbom);
        if (this.sbom != null) {
            Files.writeString(this.sbom, sbom, StandardCharsets.UTF_8);
        }
    }

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
