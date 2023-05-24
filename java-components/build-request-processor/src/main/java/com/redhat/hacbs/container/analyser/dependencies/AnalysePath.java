package com.redhat.hacbs.container.analyser.dependencies;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

import jakarta.inject.Singleton;

import com.redhat.hacbs.classfile.tracker.TrackingData;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "path")
@Singleton
public class AnalysePath extends AnalyserBase implements Runnable {

    @CommandLine.Parameters
    List<Path> paths;

    void doAnalysis(Set<String> gavs, Set<TrackingData> trackingData) throws IOException {
        Log.infof("Root paths %s", paths);
        for (var path : paths) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!Files.exists(file)) {
                        //if we have a symlink that points to nothing
                        //just ignore it
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        return handleFile(file.getFileName().toString(), Files.newInputStream(file), trackingData, gavs);
                    } catch (Exception e) {
                        Log.errorf(e, "Failed to analyse %s", file);
                        return FileVisitResult.CONTINUE;
                    }
                }
            });
        }
    }

}
