package com.redhat.hacbs.container.analyser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.cyclonedx.BomGeneratorFactory;
import org.cyclonedx.CycloneDxSchema;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "path")
@Singleton
public class AnalysePath implements Runnable {

    @CommandLine.Parameters
    List<Path> paths;

    @CommandLine.Option(names = "--allowed-sources", defaultValue = "redhat,rebuilt")
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
            Log.infof("Root paths %s", paths);
            Set<String> gavs = new HashSet<>();
            Set<TrackingData> trackingData = new HashSet<>();
            for (var path : paths) {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().endsWith(".class")) {
                            Log.debugf("Processing %s", file);
                            var data = ClassFileTracker.readTrackingInformationFromClass(Files.readAllBytes(file));
                            if (data != null) {
                                trackingData.add(data);
                                if (!allowedSources.contains(data.source)) {
                                    Log.debugf("Found GAV %s in %s", data.gav, file);
                                    gavs.add(data.gav);
                                }
                            }
                        } else if (file.getFileName().toString().endsWith(".jar")) {
                            Log.debugf("Processing %s", file);
                            var jarData = ClassFileTracker.readTrackingDataFromJar(Files.readAllBytes(file),
                                    file.getFileName().toString());
                            trackingData.addAll(jarData);
                            for (var data : jarData) {
                                if (data != null) {
                                    if (!allowedSources.contains(data.source)) {
                                        Log.debugf("Found GAV %s in %s", data.gav, file);
                                        gavs.add(data.gav);
                                    }
                                }
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            rebuild.rebuild(gavs);
            if (dependenciesCount != null) {
                Files.writeString(dependenciesCount, trackingData.size() + "");
            }
            if (untrustedDependenciesCount != null) {
                Files.writeString(untrustedDependenciesCount, gavs.size() + "");
            }

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
                bom.getComponents().add(component);
            }
            BomJsonGenerator generator = BomGeneratorFactory.createJson(CycloneDxSchema.Version.VERSION_14, bom);
            String sbom = generator.toJsonString();
            Log.infof("Generated SBOM:\n%s", sbom);
            if (this.sbom != null) {
                Files.writeString(this.sbom, sbom, StandardCharsets.UTF_8);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
