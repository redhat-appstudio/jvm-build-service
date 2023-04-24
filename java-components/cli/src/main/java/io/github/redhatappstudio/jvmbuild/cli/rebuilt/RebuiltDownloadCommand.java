package io.github.redhatappstudio.jvmbuild.cli.rebuilt;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.TreeMap;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientBuilder;
import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifact;
import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifactSpec;

import io.github.redhatappstudio.jvmbuild.cli.artifacts.GavCompleter;
import io.github.redhatappstudio.jvmbuild.cli.util.DockerManifest;
import picocli.CommandLine;

@CommandLine.Command(name = "download", mixinStandardHelpOptions = true, description = "Download log/source from a "
        + "rebuild", usageHelpAutoWidth = true)
public class RebuiltDownloadCommand
        implements Runnable {

    @CommandLine.Option(names = "-g", description = "The build to view, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The build to view, specified by RebuiltArtifact name", completionCandidates = RebuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "-d", description = "Download directory to use. Defaults to current directory")
    File targetDirectory = new File(System.getProperty("user.dir"));

    enum DownloadSelection {
        ALL,
        SOURCE,
        LOGS
    }

    @CommandLine.Option(names = { "-t", "--download-type" }, description = "What to download (ALL, SOURCE or LOGS)")
    DownloadSelection selection = DownloadSelection.ALL;

    @Override
    public void run() {
        TreeMap<String, RebuiltArtifact> builds = RebuildCompleter.createNames();

        System.out.println("### directory is " + targetDirectory + " , downloadSelection " + selection + " gav " + gav
                + " build " + artifact);

        if (gav != null) {
            Optional<RebuiltArtifact> rebuilt = builds.values().stream().filter(b -> b.getSpec().getGav().equals(gav))
                    .findFirst();

            if (rebuilt.isPresent()) {
                System.out.println("About to download " + gav + " (for " + rebuilt.get().getMetadata().getName() +
                        ") from image " + rebuilt.get().getSpec().getImage());

                try {
                    downloadImage(rebuilt.get().getSpec());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            } else {
                System.out.println("Unable to find " + gav + " in list of artifacts");
            }
        } else if (artifact != null) {
            Optional<RebuiltArtifact> rebuilt = Optional.ofNullable(builds.get(artifact));
            if (rebuilt.isPresent()) {
                System.out.println("About to download " + artifact + " (for " + rebuilt.get().getSpec().getGav() +
                        ") from image " + rebuilt.get().getSpec().getImage());

                try {
                    downloadImage(rebuilt.get().getSpec());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            } else {
                System.out.println("Unable to find " + artifact + " in list of artifacts");
            }
        }
    }

    public void downloadImage(RebuiltArtifactSpec spec)
            throws IOException {
        try (DockerClient dockerClient = DockerClientBuilder.getInstance().build()) {

            String image = spec.getImage();
            int lastColon = image.lastIndexOf(':');
            String repository = image.substring(0, lastColon);
            String tag = image.substring(lastColon + 1);

            System.out.println("### repository is " + repository + " tag " + tag);

            // Pull the image from the remote registry
            dockerClient.pullImageCmd(repository).withAuthConfig(dockerClient.authConfig()).withTag(tag).start()
                    .awaitCompletion();

            // Create a temporary directory and pull down the image tar - then need to extract the layers from that.
            Path tempPath = Files.createTempDirectory("image-extraction");
            Path savedImage = Paths.get(tempPath.toString(), "/image.tar");
            Files.copy(dockerClient.saveImageCmd(repository).withTag(tag).exec(),
                    savedImage,
                    StandardCopyOption.REPLACE_EXISTING);
            File tempDir = tempPath.toFile();

            extractTars(spec, savedImage.toFile(), tempDir);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    void extractTars(RebuiltArtifactSpec spec, File savedImage, File tempDir)
            throws IOException {
        unpackArchive(tempDir, savedImage);

        ObjectMapper objectMapper = new ObjectMapper();
        DockerManifest[] manifest = objectMapper.readValue(new File(tempDir, "manifest.json"),
                DockerManifest[].class);

        if (manifest.length != 1) {
            throw new RuntimeException("Unexpected manifest size");
        }

        System.out.println("### Found layers " + manifest[0].layers);

        // According to the contract layers 0 is the source and layers 1 is the logs. Therefore copy them out to
        // target depending upon selection configuration.
        targetDirectory.mkdirs();
        String gav = spec.getGav().replaceAll(":", "--");
        if (selection == DownloadSelection.ALL || selection == DownloadSelection.SOURCE) {
            Files.copy(Paths.get(tempDir.toString(), manifest[0].layers.get(0)),
                    new File(targetDirectory, gav + "-source.tar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (selection == DownloadSelection.ALL || selection == DownloadSelection.LOGS) {
            Files.copy(Paths.get(tempDir.toString(), manifest[0].layers.get(1)),
                    new File(targetDirectory, gav + "-logs.tar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void unpackArchive(File targetDirectory, File target) {
        try (ArchiveInputStream i = new ArchiveStreamFactory()
                .createArchiveInputStream(new BufferedInputStream(new FileInputStream(target)))) {
            extract(i, targetDirectory);
        } catch (ArchiveException | IOException e) {
            throw new RuntimeException("Caught exception unpacking archive", e);
        }
    }

    private void extract(ArchiveInputStream input, File destination) throws IOException {
        ArchiveEntry entry;
        while ((entry = input.getNextEntry()) != null) {
            if (!input.canReadEntryData(entry)) {
                throw new RuntimeException("Unable to read data entry for " + entry);
            }
            File file = new File(destination, entry.getName());
            if (entry.isDirectory()) {
                file.mkdirs();
            } else {
                file.getParentFile().mkdirs();
                Files.copy(input, file.toPath());
            }
        }
    }
}
