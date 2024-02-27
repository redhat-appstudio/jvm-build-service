package com.redhat.hacbs.management.internal.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.cyclonedx.BomParserFactory;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ManifestTemplate;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException;
import com.redhat.hacbs.management.events.InitialKubeImportCompleteEvent;
import com.redhat.hacbs.management.internal.model.BuildSBOMDiscoveryInfo;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.DependencySet;
import com.redhat.hacbs.management.model.IdentifiedDependency;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.StoredDependencyBuild;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class BuildSBOMDiscoveryManager {

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "sbom-discovery.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sbom-discovery-rebuild.enabled", defaultValue = "false")
    boolean rebuildEnabled;

    void importComplete(@Observes InitialKubeImportCompleteEvent importComplete) {
        buildSbomDiscovery();
    }

    @Scheduled(every = "1h")
    void scheduledImport() {
        buildSbomDiscovery();
    }

    public void buildSbomDiscovery() {
        if (!enabled) {
            return;
        }
        List<BuildAttempt> results = entityManager
                .createQuery(
                        "select a from BuildAttempt a left join BuildSBOMDiscoveryInfo b on b.build=a where b is null and a.successful")
                .getResultList();
        for (var i : results) {
            handleBuild(i);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    @TransactionConfiguration(timeout = 600)
    void handleBuild(BuildAttempt attempt) {
        Log.infof("Attempting to read build sbom for %s", attempt.dependencyBuild.buildIdentifier.dependencyBuildName);
        try {
            Bom bom = locateSbom(attempt.outputImage);
            DependencySet dependencySet = new DependencySet();
            dependencySet.type = "build-sbom";
            dependencySet.identifier = "build-sbom-" + attempt.dependencyBuild.buildIdentifier.dependencyBuildName + "-"
                    + attempt.buildId;
            dependencySet.dependencies = new ArrayList<>();
            if (bom.getComponents() != null) {
                for (var comp : bom.getComponents()) {
                    String gav = comp.getGroup() + ":" + comp.getName() + ":" + comp.getVersion();
                    MavenArtifact mavenArtifact = MavenArtifact.forGav(gav);
                    IdentifiedDependency identifiedDependency = new IdentifiedDependency();
                    identifiedDependency.mavenArtifact = mavenArtifact;
                    identifiedDependency.dependencySet = dependencySet;
                    identifiedDependency.source = comp.getPublisher();

                    if (comp.getProperties() != null) {
                        StringBuilder sb = new StringBuilder();
                        for (var e : comp.getProperties()) {
                            if (e.getName().equals("build-id")) {
                                identifiedDependency.buildId = e.getValue();
                            } else if (e.getName().equals("java:shaded-into")) {
                                identifiedDependency.shadedInto = e.getValue();
                            }
                            if (!sb.isEmpty()) {
                                sb.append(";");
                            }
                            sb.append(e.getName());
                            sb.append("=");
                            sb.append(e.getValue());
                        }
                        identifiedDependency.attributes = sb.toString();
                    }
                    dependencySet.dependencies.add(identifiedDependency);
                    BuildQueue existing = BuildQueue.find("mavenArtifact", mavenArtifact).firstResult();
                    if (rebuildEnabled && existing == null) {
                        List<StoredDependencyBuild> existingBuild = entityManager
                                .createQuery(
                                        "select a from StoredDependencyBuild a join a.producedArtifacts s where s=:artifact")
                                .setParameter("artifact", mavenArtifact)
                                .getResultList();
                        if (existingBuild.isEmpty()) {
                            BuildQueue queue = new BuildQueue();
                            queue.mavenArtifact = mavenArtifact;
                            queue.persistAndFlush();
                        }
                    }
                }
            }

            BuildSBOMDiscoveryInfo buildSbom = new BuildSBOMDiscoveryInfo();
            buildSbom.build = attempt;
            buildSbom.succeeded = true;
            buildSbom.dependencySet = dependencySet;
            attempt.buildSbom = buildSbom;
            buildSbom.persistAndFlush();

        } catch (Exception e) {
            Log.errorf(e, "failed to load sbom for %s", attempt.dependencyBuild.buildIdentifier.dependencyBuildName);
            BuildSBOMDiscoveryInfo queue = new BuildSBOMDiscoveryInfo();
            queue.build = attempt;
            queue.succeeded = false;
            queue.persist();
        }

    }

    Bom locateSbom(String image) throws IOException {

        try {
            ImageReference reference = ImageReference.parse(image);
            RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(),
                    reference.getRegistry(), reference.getRepository(),
                    new FailoverHttpClient(true,
                            true,
                            s -> Log.info(s.getMessage())));
            CredentialRetrieverFactory credentialRetrieverFactory = CredentialRetrieverFactory.forImage(reference,
                    (s) -> System.err.println(s.getMessage()));
            Optional<Credential> optionalCredential = credentialRetrieverFactory.dockerConfig().retrieve();
            optionalCredential.ifPresent(factory::setCredential);
            RegistryClient registryClient = factory.newRegistryClient();

            ManifestAndDigest<ManifestTemplate> manifestAndDigest = registryClient.pullManifest(reference.getTag().get(),
                    ManifestTemplate.class);
            List<BuildableManifestTemplate.ContentDescriptorTemplate> layers = ((OciManifestTemplate) manifestAndDigest
                    .getManifest()).getLayers();

            if (layers.size() == 3) {
                // According to the contract layers 0 is the source and layers 1 is the logs. Therefore copy them out to
                // target depending upon selection configuration.
                System.out.println("Located layer " + layers.get(1).getDigest().getHash() + " to download logs");

                Blob blob = registryClient.pullBlob(layers.get(1).getDigest(), s -> {
                }, s -> {
                });
                var file = Files.createTempFile("sbom-layer", ".tar.gz");
                try (var out = Files.newOutputStream(file)) {
                    blob.writeTo(out);
                    out.close();
                    try (GZIPInputStream inputStream = new GZIPInputStream(Files.newInputStream(file));
                            TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream)) {
                        var entry = tarArchiveInputStream.getNextTarEntry();
                        while (entry != null) {
                            if (entry.getName().equals("logs/build-sbom.json")) {
                                try {
                                    return BomParserFactory.createParser("{".getBytes(StandardCharsets.UTF_8))
                                            .parse(tarArchiveInputStream.readAllBytes());
                                } catch (ParseException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            entry = tarArchiveInputStream.getNextTarEntry();
                        }
                    }
                } finally {
                    Files.delete(file);
                }
            } else {
                throw new RuntimeException("Unexpected manifest size");
            }
        } catch (InvalidImageReferenceException | RegistryException | CredentialRetrievalException e) {
            throw new RuntimeException(e);
        }
        throw new RuntimeException("unable to locate SBOM");
    }

}
