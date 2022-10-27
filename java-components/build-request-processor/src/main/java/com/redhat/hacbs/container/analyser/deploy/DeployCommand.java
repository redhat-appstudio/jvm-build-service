package com.redhat.hacbs.container.analyser.deploy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import javax.enterprise.inject.spi.BeanManager;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.NoCloseInputStream;
import com.redhat.hacbs.container.analyser.dependencies.TaskRun;
import com.redhat.hacbs.container.analyser.dependencies.TaskRunResult;
import com.redhat.hacbs.resources.model.v1alpha1.Contaminant;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.logging.Log;
import picocli.CommandLine;

public abstract class DeployCommand implements Runnable {

    private static final String SLASH = "/";
    private static final String DOT_JAR = ".jar";
    private static final String DOT_POM = ".pom";
    private static final String DOT = ".";
    final BeanManager beanManager;

    final KubernetesClient kubernetesClient;

    @CommandLine.Option(required = false, names = "--allowed-sources", defaultValue = "redhat,rebuilt", split = ",")
    Set<String> allowedSources;

    @CommandLine.Option(required = true, names = "--tar-path")
    Path tarPath;

    @CommandLine.Option(required = false, names = "--task-run")
    String taskRun;

    public DeployCommand(BeanManager beanManager,
            KubernetesClient kubernetesClient) {
        this.beanManager = beanManager;
        this.kubernetesClient = kubernetesClient;
    }

    public void run() {
        try {
            java.nio.file.Path modified = Files.createTempFile("deployment", ".tar.gz");
            try {

                Set<String> gavs = new HashSet<>();

                Set<String> contaminants = new HashSet<>();
                Map<String, Set<String>> contaminatedPaths = new HashMap<>();
                Map<String, Set<String>> contaminatedGavs = new HashMap<>();
                try (TarArchiveInputStream in = new TarArchiveInputStream(
                        new GzipCompressorInputStream(Files.newInputStream(tarPath)))) {
                    TarArchiveEntry e;
                    while ((e = in.getNextTarEntry()) != null) {
                        Optional<Gav> gav = getGav(e.getName());
                        gav.ifPresent(
                                gav1 -> gavs.add(gav1.getGroupId() + ":" + gav1.getArtifactId() + ":" + gav1.getVersion()));
                        if (e.getName().endsWith(".jar")) {
                            Log.debugf("Checking %s for contaminants", e.getName());
                            var info = ClassFileTracker.readTrackingDataFromJar(new NoCloseInputStream(in), e.getName());
                            for (var i : info) {
                                if (!allowedSources.contains(i.source)) {
                                    //Set<String> result = new HashSet<>(info.stream().map(a -> a.gav).toList());
                                    Log.errorf("%s was contaminated by %s from %s", e.getName(), i.gav, i.source);
                                    gav.ifPresent(g -> contaminatedGavs.computeIfAbsent(i.gav,
                                            s -> new HashSet<>())
                                            .add(g.getGroupId() + ":" + g.getArtifactId() + ":" + g.getVersion()));
                                    contaminants.add(i.gav);
                                    int index = e.getName().lastIndexOf("/");
                                    if (index != -1) {
                                        contaminatedPaths
                                                .computeIfAbsent(e.getName().substring(0, index), s -> new HashSet<>())
                                                .add(i.gav);
                                    } else {
                                        contaminatedPaths.computeIfAbsent("", s -> new HashSet<>()).add(i.gav);
                                    }
                                }

                            }
                        }
                    }
                }
                if (gavs.isEmpty()) {
                    Log.errorf("No content to deploy found in deploy tarball");
                    try (TarArchiveInputStream in = new TarArchiveInputStream(
                            new GzipCompressorInputStream(Files.newInputStream(tarPath)))) {
                        TarArchiveEntry e;
                        while ((e = in.getNextTarEntry()) != null) {
                            Log.errorf("Contents: %s", e.getName());
                        }
                    }
                    throw new RuntimeException("deploy failed");
                }
                //we still deploy, but without the contaminates
                java.nio.file.Path deployFile = tarPath;
                boolean allSkipped = false;
                if (!contaminants.isEmpty()) {
                    allSkipped = true;
                    deployFile = modified;
                    try (var out = Files.newOutputStream(modified)) {
                        try (var archive = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
                            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                            archive.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                            archive.setAddPaxHeadersForNonAsciiNames(true);
                            try (TarArchiveInputStream in = new TarArchiveInputStream(
                                    new GzipCompressorInputStream(Files.newInputStream(tarPath)))) {
                                TarArchiveEntry e;
                                while ((e = in.getNextTarEntry()) != null) {
                                    boolean skip = false;
                                    for (var i : contaminatedPaths.keySet()) {
                                        if (e.getName().startsWith(i)) {
                                            skip = true;
                                            break;
                                        }
                                    }
                                    if (!skip) {
                                        if (e.getName().endsWith(".jar")) {
                                            allSkipped = false;
                                        }
                                        Log.infof("Adding resource %s", e.getName());
                                        TarArchiveEntry archiveEntry = new TarArchiveEntry(e.getName());
                                        archiveEntry.setSize(e.getSize());
                                        archiveEntry.setModTime(e.getModTime());
                                        archiveEntry.setGroupId(e.getLongGroupId());
                                        archiveEntry.setUserId(e.getLongUserId());
                                        archiveEntry.setMode(e.getMode());
                                        archive.putArchiveEntry(archiveEntry);
                                        try {
                                            byte[] buf = new byte[1024];
                                            int r;
                                            while ((r = in.read(buf)) > 0) {
                                                archive.write(buf, 0, r);
                                            }
                                        } finally {
                                            archive.closeArchiveEntry();
                                        }
                                    }

                                }
                            }
                        }
                    }
                }
                Log.infof("Contaminants: %s", contaminatedPaths);
                //update the DB with contaminant information

                if (taskRun != null) {

                    Resource<TaskRun> taskRunResource = kubernetesClient.resources(TaskRun.class)
                            .withName(taskRun);
                    List<Contaminant> newContaminates = new ArrayList<>();
                    for (var i : contaminatedGavs.entrySet()) {
                        newContaminates.add(new Contaminant(i.getKey(), new ArrayList<>(i.getValue())));
                    }
                    String serialisedContaminants = new ObjectMapper().writeValueAsString(newContaminates);
                    taskRunResource.editStatus(new UnaryOperator<TaskRun>() {
                        @Override
                        public TaskRun apply(TaskRun taskRun) {
                            List<TaskRunResult> results = new ArrayList<>();
                            if (taskRun.getStatus().getTaskResults() != null) {
                                results.addAll(taskRun.getStatus().getTaskResults());
                            }
                            Log.infof("Updating results %s with contaminants %s and deployed resources %s",
                                    taskRun.getMetadata().getName(), serialisedContaminants, gavs);
                            results.add(new TaskRunResult("CONTAMINANTS", serialisedContaminants));
                            results.add(new TaskRunResult("DEPLOYED_RESOURCES", String.join(",", gavs)));
                            taskRun.getStatus().setTaskResults(results);
                            return taskRun;
                        }
                    });
                }

                if (!allSkipped) {
                    try {
                        doDeployment(deployFile);
                    } catch (Throwable t) {
                        Log.error("Deployment failed", t);
                        flushLogs();
                        throw t;
                    }
                } else {
                    Log.errorf("Skipped deploying from task run %s as all artifacts were contaminated", taskRun);
                }
            } finally {
                if (Files.exists(modified)) {
                    Files.delete(modified);
                }
            }
        } catch (Exception e) {
            Log.error("Deployment failed", e);
            throw new RuntimeException(e);
        }
    }

    protected abstract void doDeployment(Path deployFile) throws Exception;

    private void flushLogs() {
        System.err.flush();
        System.out.flush();
    }

    private Optional<Gav> getGav(String entryName) {
        if (entryName.startsWith("./")) {
            entryName = entryName.substring(2);
        }
        if (entryName.endsWith(DOT_JAR) || entryName.endsWith(DOT_POM)) {

            List<String> pathParts = List.of(entryName.split(SLASH));
            int numberOfParts = pathParts.size();

            String version = pathParts.get(numberOfParts - 2);
            String artifactId = pathParts.get(numberOfParts - 3);
            List<String> groupIdList = pathParts.subList(0, numberOfParts - 3);
            String groupId = String.join(DOT, groupIdList);

            return Optional.of(new Gav(groupId, artifactId, version, null));
        }
        return Optional.empty();
    }

}
