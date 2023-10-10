package io.github.redhatappstudio.jvmbuild.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;

import picocli.CommandLine;

@CommandLine.Command(name = "diagnostic", mixinStandardHelpOptions = true, description = "Print diagnostic information")
public class DiagnosticCommand {
    @CommandLine.Command(mixinStandardHelpOptions = true, description = "Dump class tracking information")
    public void classdump(@CommandLine.Parameters() Path fileName) {
        String name = fileName.toString();
        try {
            System.out.println("Looking for " + fileName);
            Set<TrackingData> result = ClassFileTracker.readTrackingDataFromFile(Files.newInputStream(fileName), name);

            if (result.isEmpty()) {
                System.out.println("No tracking data found");
            } else {
                System.out.println("Found " + result.size() + " tracking data:");
                for (TrackingData data : result) {
                    System.out.println(data.toString());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
