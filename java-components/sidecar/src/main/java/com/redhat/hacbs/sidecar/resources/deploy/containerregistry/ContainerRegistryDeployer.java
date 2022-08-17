package com.redhat.hacbs.sidecar.resources.deploy.containerregistry;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import com.redhat.hacbs.sidecar.resources.deploy.Deployer;
import com.redhat.hacbs.sidecar.resources.deploy.DeployerUtil;

import io.quarkus.logging.Log;

@ApplicationScoped
@Named("ContainerRegistryDeployer")
public class ContainerRegistryDeployer implements Deployer {

    private final String host;
    private final int port;
    private final String owner;
    private final Optional<String> token;
    private final String repository;
    private final boolean insecure;
    private final Optional<String> prependTag;
    private final Set<String> doNotDeploy;

    public ContainerRegistryDeployer(
            @ConfigProperty(name = "containerregistrydeployer.host", defaultValue = "quay.io") String host,
            @ConfigProperty(name = "containerregistrydeployer.port", defaultValue = "443") int port,
            @ConfigProperty(name = "containerregistrydeployer.owner", defaultValue = "hacbs") String owner,
            @ConfigProperty(name = "containerregistrydeployer.token") Optional<String> token,
            @ConfigProperty(name = "containerregistrydeployer.repository", defaultValue = "artifact-deployments") String repository,
            @ConfigProperty(name = "containerregistrydeployer.insecure", defaultValue = "false") boolean insecure,
            @ConfigProperty(name = "containerregistrydeployer.prepend-tag", defaultValue = "") Optional<String> prependTag,
            @ConfigProperty(name = "ignored-artifacts", defaultValue = "") Optional<Set<String>> doNotDeploy) {

        this.host = host;
        this.port = port;
        this.owner = owner;
        this.token = token;
        this.repository = repository;
        this.insecure = insecure;
        this.prependTag = prependTag;
        this.doNotDeploy = doNotDeploy.orElse(Set.of());

    }

    @Override
    public void deployArchive(Path tarGzFile) throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);

        // Read the tar to get the gavs and files
        ImageData imageData = getImageData(tarGzFile);

        // Create the image layers
        createImages(tarGzFile, imageData);
    }

    private void createImages(Path tarGzFile, ImageData imageData)
            throws InvalidImageReferenceException, InterruptedException, RegistryException, IOException,
            CacheDirectoryCreationException, ExecutionException {

        String imageName = createImageName(imageData);
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (token.isPresent()) {
            registryImage = registryImage.addCredential(Credential.OAUTH2_TOKEN_USER_NAME, token.get());
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);

        Set<Gav> gavs = imageData.getGavs();

        for (Gav gav : gavs) {
            containerizer = containerizer.withAdditionalTag(gav.getTag());
        }

        AbsoluteUnixPath imageRoot = AbsoluteUnixPath.get(SLASH);
        JibContainerBuilder containerBuilder = Jib.fromScratch()
                .setFormat(ImageFormat.OCI)
                .addLabel("groupId", imageData.getGroupIds())
                .addLabel("version", imageData.getVersions())
                .addLabel("artifactId", imageData.getArtifactIds());

        List<Path> layers = getLayers(imageData.getArtifactsPath());
        for (Path layer : layers) {
            containerBuilder = containerBuilder.addLayer(List.of(layer), imageRoot);
        }

        Log.debugf("Image %s created", imageName);

        containerBuilder.containerize(containerizer);
    }

    private String createImageName(ImageData imageData) {
        String imageName = UUID.randomUUID().toString();
        return host + DOUBLE_POINT + port + SLASH + owner + SLASH + repository + SLASH + imageName
                + DOUBLE_POINT + imageData.getVersions();
    }

    private List<Path> getLayers(Path artifacts)
            throws IOException {

        // TODO: For now we create dummy source and logs
        Path layer1Path = Files.createTempFile("source", ".txt");
        Path layer2Path = Files.createTempFile("logs", ".txt");

        Log.debug("\n Container details:\n"
                + "\t layer 1 (source) " + layer1Path.toString() + "\n"
                + "\t layer 2 (logs) " + layer2Path.toString() + "\n"
                + "\t layer 3 (artifacts) " + artifacts.toString());

        return List.of(layer1Path, layer2Path, artifacts);
    }

    private ImageData getImageData(Path tarGzFile) throws IOException {
        Path outputPath = Paths.get(Files.createTempDirectory(HACBS).toString(), ARTIFACTS);
        Files.createDirectories(outputPath);

        try (InputStream tarInput = Files.newInputStream(tarGzFile)) {
            ImageData imageData = new ImageData(outputPath, extractTarArchive(tarInput, outputPath.toString()));
            return imageData;
        }
    }

    private Set<Gav> extractTarArchive(InputStream tarInput, String folder) throws IOException {

        Set<Gav> gavs = new HashSet<>();

        try (GZIPInputStream inputStream = new GZIPInputStream(tarInput);
                TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream)) {

            for (TarArchiveEntry entry = tarArchiveInputStream.getNextTarEntry(); entry != null; entry = tarArchiveInputStream
                    .getNextTarEntry()) {
                if (!DeployerUtil.shouldIgnore(doNotDeploy, entry.getName())) {
                    Optional<Gav> maybeGav = extractEntry(entry, tarArchiveInputStream, folder);
                    if (maybeGav.isPresent()) {
                        gavs.add(maybeGav.get());
                    }
                }
            }
        }
        return gavs;
    }

    private Optional<Gav> extractEntry(ArchiveEntry entry, InputStream tar, String folder) throws IOException {
        final String path = folder + File.separator + entry.getName();
        if (entry.isDirectory()) {
            new File(path).mkdirs();
        } else {
            new File(path).getParentFile().mkdirs();
            int count;
            byte[] data = new byte[BUFFER_SIZE];
            try (FileOutputStream os = new FileOutputStream(path);
                    BufferedOutputStream dest = new BufferedOutputStream(os, BUFFER_SIZE)) {
                while ((count = tar.read(data, 0, BUFFER_SIZE)) != -1) {
                    dest.write(data, 0, count);
                }
            }
            return getGav(entry.getName());
        }
        return Optional.empty();
    }

    private Optional<Gav> getGav(String entryName) {
        if (entryName.endsWith(DOT_JAR) || entryName.endsWith(DOT_POM)) {

            List<String> pathParts = List.of(entryName.split(SLASH));
            int numberOfParts = pathParts.size();

            String version = pathParts.get(numberOfParts - 2);
            String artifactId = pathParts.get(numberOfParts - 3);
            List<String> groupIdList = pathParts.subList(0, numberOfParts - 3);
            String groupId = String.join(DOT, groupIdList);

            String tag = DeployerUtil.sha256sum(groupId, artifactId, version);
            if (prependTag.isPresent()) {
                tag = prependTag.get() + UNDERSCORE + tag;
            }
            if (tag.length() > 128) {
                tag = tag.substring(0, 128);
            }

            return Optional.of(new Gav(groupId, artifactId, version, tag));
        }
        return Optional.empty();
    }

    private static final String DOUBLE_POINT = ":";
    private static final String SLASH = "/";
    private static final String UNDERSCORE = "_";
    private static final String DOT_JAR = ".jar";
    private static final String DOT_POM = ".pom";
    private static final String DOT = ".";
    private static final String HACBS = "hacbs";
    private static final String ARTIFACTS = "artifacts";
    private static final int BUFFER_SIZE = 4096;
}
