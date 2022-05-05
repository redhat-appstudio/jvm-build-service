package com.redhat.hacbs.container.analyser;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "path")
public class AnalysePath implements Runnable {

    @CommandLine.Parameters
    List<Path> paths;

    @CommandLine.Option(names = "--allowed-sources", defaultValue = "redhat,rebuilt")
    Set<String> allowedSources;

    @Inject
    RebuildService rebuild;

    @Override
    public void run() {
        try {
            Log.infof("Root paths %s", paths);
            Set<String> gavs = new HashSet<>();
            for (var path : paths) {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().endsWith(".class")) {
                            Log.debugf("Processing %s", file);
                            var data = ClassFileTracker.readTrackingInformationFromClass(Files.readAllBytes(file));
                            if (data != null) {
                                if (!allowedSources.contains(data.source)) {
                                    Log.debugf("Found GAV %s in %s", data.gav, file);
                                    gavs.add(data.gav);
                                }
                            }
                        } else if (file.getFileName().toString().endsWith(".jar")) {
                            Log.debugf("Processing %s", file);
                            var jarData = ClassFileTracker.readTrackingDataFromJar(Files.readAllBytes(file));
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

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
