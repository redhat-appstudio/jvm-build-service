package com.redhat.hacbs.container.deploy;

import static com.redhat.hacbs.classfile.tracker.TrackingData.extractClassifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.enterprise.inject.spi.BeanManager;

import org.apache.commons.lang3.StringUtils;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;
import com.redhat.hacbs.common.maven.GAV;
import com.redhat.hacbs.container.results.ResultsUpdater;
import com.redhat.hacbs.recipes.util.FileUtil;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildstatus.Contaminates;
import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "verify")
public class BuildVerifyCommand implements Runnable {

    private static final String DOT_JAR = ".jar";
    private static final String DOT_POM = ".pom";
    private static final String DOT = ".";
    private static final Set<String> ALLOWED_CONTAMINANTS = Set.of("-tests.jar");
    public static final String BUILD_ID = "build-id";
    final BeanManager beanManager;
    final ResultsUpdater resultsUpdater;

    @CommandLine.Option(names = "--allowed-sources", defaultValue = "redhat,rebuilt", split = ",")
    Set<String> allowedSources;

    @CommandLine.Option(required = true, names = "--path")
    Path deploymentPath;

    @CommandLine.Option(names = "--task-run-name")
    String taskRun;

    @CommandLine.Option(names = "--logs-path")
    Path logsPath;

    @CommandLine.Option(required = true, names = "--scm-uri")
    String scmUri;

    @CommandLine.Option(required = true, names = "--scm-commit")
    String commit;

    @CommandLine.Option(names = "--build-id")
    String buildId;

    public BuildVerifyCommand(BeanManager beanManager,
            ResultsUpdater resultsUpdater) {
        this.beanManager = beanManager;
        this.resultsUpdater = resultsUpdater;
    }

    public void run() {
        try {
            Set<String> gavs = new HashSet<>();
            Map<String, Set<String>> contaminatedPaths = new HashMap<>();
            Map<String, Contaminates> contaminatedGavs = new HashMap<>();

            // Represents directories that should not be deployed i.e. if a single artifact (barring test jars) is
            // contaminated then none of the artifacts will be deployed.
            Set<Path> toRemove = new HashSet<>();
            Map<Path, GAV> jarFiles = new HashMap<>();

            if (!deploymentPath.toFile().exists()) {
                Log.warnf("No deployed artifacts found. Has the build been correctly configured to deploy?");
                throw new RuntimeException("Deploy failed");
            }
            Files.walkFileTree(deploymentPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path path = deploymentPath.relativize(file);
                    String name = path.toString();
                    Optional<GAV> gav = getGav(name);
                    if (gav.isPresent()) {
                        var coords = gav.get().stringForm();
                        gavs.add(coords);
                        Log.debugf("Checking %s with GAV %s for contaminants", path.getFileName(), coords);
                    } else {
                        Log.debugf("Checking %s for contaminants", path.getFileName());
                    }
                    //we check every file as we also want to catch .tar.gz etc
                    var info = ClassFileTracker.readTrackingDataFromFile(Files.newInputStream(file), name);
                    for (var i : info) {
                        Log.errorf("%s was contaminated by %s from %s", path.getFileName(), i.gav, i.source);
                        if (ALLOWED_CONTAMINANTS.stream().noneMatch(a -> file.getFileName().toString().endsWith(a))) {
                            int index = name.lastIndexOf("/");
                            boolean allowed = allowedSources.contains(i.source);
                            if (!allowed) {
                                if (index != -1) {
                                    contaminatedPaths.computeIfAbsent(name.substring(0, index),
                                            s -> new HashSet<>()).add(i.gav);
                                } else {
                                    contaminatedPaths.computeIfAbsent("", s -> new HashSet<>()).add(i.gav);
                                }
                                toRemove.add(file.getParent());
                            }
                            gav.ifPresent(g -> contaminatedGavs.computeIfAbsent(i.gav, s -> {
                                Contaminates contaminates = new Contaminates();
                                contaminates.setGav(i.gav);
                                contaminates.setAllowed(allowed);
                                contaminates.setSource(i.source);
                                contaminates.setBuildId(i.getAttributes().get(BUILD_ID));
                                contaminates.setContaminatedArtifacts(new ArrayList<>());
                                return contaminates;
                            })
                                    .getContaminatedArtifacts()
                                    .add(g.getGroupId() + ":" + g.getArtifactId() + ":" + g.getVersion()));

                        } else {
                            Log.debugf("Ignoring contaminant for %s", file.getFileName());
                        }

                    }
                    if (gav.isPresent()) {
                        //now add our own tracking data
                        if (name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")) {
                            jarFiles.put(file, gav.get());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            for (var e : jarFiles.entrySet()) {
                Path file = e.getKey();
                GAV gav = e.getValue();
                try {
                    String fileName = file.getFileName().toString();
                    Path temp = file.getParent().resolve(fileName + ".temp");
                    String classifier = extractClassifier(gav.getArtifactId(), gav.getVersion(), fileName);
                    Map<String, String> attributes;
                    if (StringUtils.isNotBlank(classifier)) {
                        attributes = Map.of("scm-uri", scmUri, "scm-commit", commit, BUILD_ID, buildId, "classifier", classifier);
                    } else {
                        attributes = Map.of("scm-uri", scmUri, "scm-commit", commit, BUILD_ID, buildId);
                    }
                    ClassFileTracker.addTrackingDataToJar(Files.newInputStream(file),
                            new TrackingData(
                                    gav.getGroupId() + ":" + gav.getArtifactId() + ":"
                                            + gav.getVersion(),
                                    "rebuilt",
                                    attributes),
                            Files.newOutputStream(temp), false);
                    Files.delete(file);
                    Files.move(temp, file);
                    try (Stream<Path> pathStream = Files.list(file.getParent())) {
                        pathStream.filter(s -> s.getFileName().toString().startsWith(fileName + "."))
                                .forEach(f -> {
                                    try {
                                        Files.delete(f);
                                    } catch (IOException ex) {
                                        throw new RuntimeException(ex);
                                    }
                                });
                    }

                    Files.writeString(file.getParent().resolve(fileName + ".md5"),
                            HashUtil.md5(Files.newInputStream(file)));
                    Files.writeString(file.getParent().resolve(fileName + ".sha1"),
                            HashUtil.sha1(Files.newInputStream(file)));
                } catch (Exception ex) {
                    Log.errorf(ex, "Failed to instrument %s", file);
                }
            }
            for (var i : toRemove) {
                Log.errorf("Removing %s as it is contaminated", i);
                FileUtil.deleteRecursive(i);
            }

            //update the DB with contaminant information
            Log.infof("Contaminants: %s", contaminatedPaths);
            Log.infof("GAVs to deploy: %s", gavs);
            if (gavs.isEmpty()) {
                Log.errorf("No content to verify found in directory");

                Files.walkFileTree(deploymentPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Log.errorf("Contents: %s", file);
                        return FileVisitResult.CONTINUE;
                    }
                });
                throw new RuntimeException("Verify failed");
            }
            for (var i : contaminatedGavs.entrySet()) {
                if (!i.getValue().getAllowed()) {
                    i.getValue().getContaminatedArtifacts().forEach(gavs::remove);
                }
            }

            //we still deploy, but without the contaminates
            // This means the build failed to produce any deployable output.
            // If everything is contaminated we still need the task to succeed so we can resolve the contamination.
            if (taskRun != null) {
                List<Contaminates> newContaminates = new ArrayList<>();
                for (var i : contaminatedGavs.entrySet()) {
                    newContaminates.add(i.getValue());
                }
                String serialisedContaminants = ResultsUpdater.MAPPER.writeValueAsString(newContaminates);
                Log.infof("Updating results %s for verified resources %s with contaminants %s",
                        taskRun, gavs, serialisedContaminants);
                resultsUpdater.updateResults(taskRun, Map.of(
                        "CONTAMINANTS", serialisedContaminants,
                        "DEPLOYED_RESOURCES", String.join(",", gavs)));
            }
        } catch (Exception e) {
            Log.error("Verification failed", e);
            throw new RuntimeException(e);
        }
    }

    private Optional<GAV> getGav(String entryName) {
        if (entryName.startsWith("." + File.separator)) {
            entryName = entryName.substring(2);
        }
        if (entryName.endsWith(DOT_JAR) || entryName.endsWith(DOT_POM)) {
            List<String> pathParts = List.of(StringUtils.split(entryName, File.separatorChar));
            int numberOfParts = pathParts.size();

            String version = pathParts.get(numberOfParts - 2);
            String artifactId = pathParts.get(numberOfParts - 3);
            List<String> groupIdList = pathParts.subList(0, numberOfParts - 3);
            String groupId = String.join(DOT, groupIdList);

            return Optional.of(GAV.create(groupId, artifactId, version));
        }
        return Optional.empty();
    }
}
