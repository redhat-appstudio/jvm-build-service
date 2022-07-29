package com.redhat.hacbs.container.analyser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import org.cyclonedx.BomGeneratorFactory;
import org.cyclonedx.CycloneDxSchema;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Property;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;

import io.quarkus.logging.Log;
import picocli.CommandLine;

public abstract class AnalyserBase implements Runnable {

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

    @Override
    public void run() {
        try {
            Set<String> gavs = new HashSet<>();
            Set<TrackingData> trackingData = new HashSet<>();
            doAnalysis(gavs, trackingData);
            rebuild.rebuild(gavs);
            writeResults(gavs, trackingData);
            writeSbom(trackingData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    abstract void doAnalysis(Set<String> gavs, Set<TrackingData> trackingData) throws Exception;

    void writeSbom(Set<TrackingData> trackingData) throws IOException {
        //now build a cyclone DX bom file
        final Bom bom = new Bom();
        bom.setComponents(new ArrayList<>());
        for (var i : trackingData) {
            var split = i.gav.split(":");
            String group = split[0];
            String name = split[1];
            String version = split[2];

            Component component = new Component();
            component.setType(Component.Type.LIBRARY);
            component.setGroup(group);
            component.setName(name);
            component.setVersion(version);
            component.setPublisher(i.source);
            component.setPurl(String.format("pkg:maven/%s/%s@%s", group, name, version));

            Property packageTypeProperty = new Property();
            packageTypeProperty.setName("package:type");
            packageTypeProperty.setValue("maven");

            Property packageLanguageProperty = new Property();
            packageLanguageProperty.setName("package:language");
            packageLanguageProperty.setValue("java");

            component.setProperties(List.of(packageTypeProperty, packageLanguageProperty));

            bom.getComponents().add(component);
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
    }

    FileVisitResult handleFile(String fileName, InputStream contents, Set<TrackingData> trackingData, Set<String> gavs)
            throws IOException {
        if (fileName.endsWith(".class")) {
            Log.debugf("Processing %s", fileName);
            var data = ClassFileTracker.readTrackingInformationFromClass(contents.readAllBytes());
            if (data != null) {
                trackingData.add(data);
                if (!allowedSources.contains(data.source)) {
                    Log.debugf("Found GAV %s in %s", data.gav, fileName);
                    gavs.add(data.gav);
                }
            }
        } else if (fileName.endsWith(".jar")) {
            Log.debugf("Processing %s", fileName);
            var jarData = ClassFileTracker.readTrackingDataFromJar(contents,
                    fileName);
            trackingData.addAll(jarData);
            for (var data : jarData) {
                if (data != null) {
                    if (!allowedSources.contains(data.source)) {
                        Log.debugf("Found GAV %s in %s", data.gav, fileName);
                        gavs.add(data.gav);
                    }
                }
            }
        }
        return FileVisitResult.CONTINUE;
    }
}
