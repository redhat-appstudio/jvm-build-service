package com.redhat.hacbs.artifactcache.resources;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Singleton;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestPath;

import com.redhat.hacbs.artifactcache.artifactwatch.RebuiltArtifacts;
import com.redhat.hacbs.artifactcache.deploy.Deployer;
import com.redhat.hacbs.artifactcache.deploy.Gav;
import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.NoCloseInputStream;
import com.redhat.hacbs.resources.model.v1alpha1.Contaminant;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifact;
import com.redhat.hacbs.resources.util.ResourceNameUtils;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.common.annotation.Blocking;

@Path("/v1/deploy")
@Blocking
@Singleton
@Startup
public class DeployResource {

    private static final String SLASH = "/";
    private static final String DOT_JAR = ".jar";
    private static final String DOT_POM = ".pom";
    private static final String DOT = ".";
    final Deployer deployer;
    final BeanManager beanManager;

    final KubernetesClient kubernetesClient;

    final RebuiltArtifacts rebuiltArtifacts;

    Set<String> allowedSources;

    public DeployResource(BeanManager beanManager,
            @ConfigProperty(name = "deployer") Optional<String> deployer,
            @ConfigProperty(name = "registry.token") Optional<String> token,
            @ConfigProperty(name = "allowed-sources", defaultValue = "redhat,rebuilt") Optional<Set<String>> allowedSources,
            RebuiltArtifacts rebuiltArtifacts,
            KubernetesClient kubernetesClient) {
        this.beanManager = beanManager;
        this.deployer = getDeployer(deployer, token);
        this.allowedSources = allowedSources.orElse(Set.of());
        this.kubernetesClient = kubernetesClient;
        this.rebuiltArtifacts = rebuiltArtifacts;
        Log.debugf("Using %s deployer", deployer);
    }

    @POST
    @Path("/{buildId}")
    public void deployArchive(@RestPath String buildId, InputStream data) throws Exception {
        java.nio.file.Path temp = Files.createTempFile("deployment", ".tar.gz");
        java.nio.file.Path modified = Files.createTempFile("deployment", ".tar.gz");
        try {
            try (var out = Files.newOutputStream(temp)) {
                byte[] buf = new byte[1024];
                int r;
                while ((r = data.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            }

            Set<Gav> gavs = new HashSet<>();

            Set<String> contaminants = new HashSet<>();
            Map<String, Set<String>> contaminatedPaths = new HashMap<>();
            Map<String, Set<String>> contaminatedGavs = new HashMap<>();
            try (TarArchiveInputStream in = new TarArchiveInputStream(
                    new GzipCompressorInputStream(Files.newInputStream(temp)))) {
                TarArchiveEntry e;
                while ((e = in.getNextTarEntry()) != null) {
                    Optional<Gav> gav = getGav(e.getName());
                    gav.ifPresent(gavs::add);
                    if (e.getName().endsWith(".jar")) {
                        Log.infof("Checking %s for contaminants", e.getName());
                        var info = ClassFileTracker.readTrackingDataFromJar(new NoCloseInputStream(in), e.getName());
                        if (info != null) {
                            for (var i : info) {
                                if (!allowedSources.contains(i.source)) {
                                    //Set<String> result = new HashSet<>(info.stream().map(a -> a.gav).toList());
                                    Log.errorf("%s was contaminated by %s", e.getName(), i.gav);
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
            }
            //we still deploy, but without the contaminates
            java.nio.file.Path deployFile = temp;
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
                                new GzipCompressorInputStream(Files.newInputStream(temp)))) {
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

            Resource<DependencyBuild> dependencyBuildResource = kubernetesClient.resources(DependencyBuild.class)
                    .withName(buildId);
            List<Contaminant> newContaminates = new ArrayList<>();
            for (var i : contaminatedGavs.entrySet()) {
                newContaminates.add(new Contaminant(i.getKey(), new ArrayList<>(i.getValue())));
            }
            Log.infof("Updating build %s with contaminants %s", buildId, newContaminates);

            //now create the RebuiltArtifacts to make sure that these can be retrieved
            //the build may have created more GAV's than we have ArtifactBuild's for, so we
            //create the RebuiltArtifact so that these extra artifacts are still usable

            var rebuiltResource = kubernetesClient.resources(RebuiltArtifact.class);
            for (var i : gavs) {
                RebuiltArtifact rebuiltArtifact = new RebuiltArtifact();
                ObjectMeta objectMeta = new ObjectMeta();
                String gav = i.getGroupId() + ":" + i.getArtifactId() + ":" + i.getVersion();
                String name = ResourceNameUtils.nameFromGav(gav);
                objectMeta.setName(name);
                objectMeta.setAdditionalProperty("gav", gav);
                rebuiltArtifact.setMetadata(objectMeta);
                rebuiltArtifact.getSpec().setGav(gav);
                try {
                    rebuiltResource.create(rebuiltArtifact);
                } catch (Exception e) {
                    if (!e.getMessage().contains("already exists")) { //there is no good way to check this
                        Log.errorf(e, "Failed to create RebuiltArtifact %s", name);
                    }
                }
            }

            var dependencyBuild = dependencyBuildResource.get();
            if (newContaminates.isEmpty()) {
                dependencyBuild.getStatus().setContaminates(new ArrayList<>());
            } else {
                dependencyBuild.getStatus().setContaminates(newContaminates);
            }
            dependencyBuildResource.replaceStatus(dependencyBuild);

            if (!allSkipped) {
                try {
                    deployer.deployArchive(deployFile);
                } catch (Throwable t) {
                    Log.error("Deployment failed", t);
                    flushLogs();
                    throw t;
                }
            } else {
                Log.errorf("Skipped deploying %s as all artifacts were contaminated", buildId);
            }
        } finally {
            Files.delete(temp);
            if (Files.exists(modified)) {
                Files.delete(modified);
            }
        }
    }

    private Deployer getDeployer(Optional<String> name, Optional<String> registryToken) {
        //if the registry token is defined and not the deployer we default to the container
        //registry deployer
        String actualName = name.orElse(registryToken.isPresent() ? "ContainerRegistryDeployer" : "S3Deployer");
        Bean<Deployer> bean = (Bean<Deployer>) beanManager.getBeans(actualName).iterator().next();
        CreationalContext<Deployer> ctx = beanManager.createCreationalContext(bean);
        return (Deployer) beanManager.getReference(bean, Deployer.class, ctx);

    }

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
