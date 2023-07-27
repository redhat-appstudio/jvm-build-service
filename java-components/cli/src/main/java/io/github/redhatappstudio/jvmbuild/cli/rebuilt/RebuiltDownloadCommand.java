package io.github.redhatappstudio.jvmbuild.cli.rebuilt;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.parsers.JsonParser;
import org.cyclonedx.parsers.Parser;

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
import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifact;
import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifactSpec;

import io.github.redhatappstudio.jvmbuild.cli.artifacts.GavCompleter;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "download", mixinStandardHelpOptions = true, description = "Download log/source from a "
        + "rebuild", usageHelpAutoWidth = true)
public class RebuiltDownloadCommand
        implements Runnable {

    enum DownloadSelection {
        ALL,
        SOURCE,
        LOGS
    }

    @CommandLine.Option(names = "-g", description = "The build to view, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The build to view, specified by RebuiltArtifact name", completionCandidates = RebuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "-s", description = "Path to an sbom to download all referenced artifacts", completionCandidates = RebuildCompleter.class)
    File sBom;

    @CommandLine.Option(names = "-d", description = "Download directory to use. Defaults to current directory")
    File targetDirectory = new File(System.getProperty("user.dir"));

    @CommandLine.Option(names = { "-t",
            "--download-type" }, description = "What to download (ALL, SOURCE or LOGS). Default: ${DEFAULT-VALUE}", defaultValue = "ALL")
    DownloadSelection selection = DownloadSelection.ALL;

    @Override
    public void run() {
        Map<String, RebuiltArtifact> builds = RebuildCompleter.createNames();

        try {
            if (gav != null) {
                Optional<RebuiltArtifact> rebuilt = builds.values().stream().filter(b -> b.getSpec().getGav().equals(gav))
                        .findFirst();
                if (rebuilt.isPresent()) {
                    System.out.println("About to download " + gav + " (for " + rebuilt.get().getMetadata().getName() +
                            ") from image " + rebuilt.get().getSpec().getImage());
                    downloadImage(rebuilt.get().getSpec());
                } else {
                    System.out.println("Unable to find " + gav + " in list of artifacts");
                }
            } else if (artifact != null) {
                Optional<RebuiltArtifact> rebuilt = Optional.ofNullable(builds.get(artifact));
                if (rebuilt.isPresent()) {
                    System.out.println("About to download " + artifact + " (for " + rebuilt.get().getMetadata().getName() +
                            ") from image " + rebuilt.get().getSpec().getImage());
                    downloadImage(rebuilt.get().getSpec());
                } else {
                    System.out.println("Unable to find " + artifact + " in list of artifacts");
                }
            } else if (sBom != null) {
                if (!sBom.exists()) {
                    System.out.println("Unable to find sbom " + sBom);
                } else {
                    try {
                        Parser parser = new JsonParser();
                        parser.parse(sBom).getComponents().forEach(c -> {
                            String gav = c.getGroup() + ":" + c.getName() + ":" + c.getVersion();
                            Optional<RebuiltArtifact> rebuilt = builds.values().stream()
                                    .filter(b -> b.getSpec().getGav().equals(gav)).findFirst();
                            if (rebuilt.isPresent()) {
                                try {
                                    downloadImage(rebuilt.get().getSpec());
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            } else {
                                System.out.println("Unable to find " + gav + " in list of artifacts");
                            }
                        });
                    } catch (ParseException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void downloadImage(RebuiltArtifactSpec spec)
            throws IOException {

        try {
            ImageReference reference = ImageReference.parse(spec.getImage());
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
                String gav = spec.getGav().replaceAll(":", "--");
                if (selection == DownloadSelection.ALL || selection == DownloadSelection.SOURCE) {
                    System.out.println("Located layer " + layers.get(0).getDigest().getHash() + " to download sources");
                    writeLayer(registryClient, layers.get(0), Paths.get(targetDirectory.toString(), gav +
                            "-source.tar.gz"));
                }
                if (selection == DownloadSelection.ALL || selection == DownloadSelection.LOGS) {
                    System.out.println("Located layer " + layers.get(1).getDigest().getHash() + " to download logs");
                    writeLayer(registryClient, layers.get(1), Paths.get(targetDirectory.toString(), gav +
                            "-logs.tar.gz"));
                }
            } else {
                throw new RuntimeException("Unexpected manifest size");
            }
        } catch (InvalidImageReferenceException | RegistryException | CredentialRetrievalException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeLayer(RegistryClient registryClient,
            BuildableManifestTemplate.ContentDescriptorTemplate layer, Path targetPath)
            throws IOException {
        Blob blob = registryClient.pullBlob(layer.getDigest(), s -> {
        }, s -> {
        });

        try (OutputStream tarOutputStream = Files.newOutputStream(targetPath)) {
            blob.writeTo(tarOutputStream);
        }
    }
}
