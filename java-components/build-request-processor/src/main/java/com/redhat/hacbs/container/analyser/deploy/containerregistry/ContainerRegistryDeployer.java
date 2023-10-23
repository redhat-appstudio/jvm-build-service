package com.redhat.hacbs.container.analyser.deploy.containerregistry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FilePermissions;
import com.google.cloud.tools.jib.api.buildplan.FilePermissionsProvider;
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import com.redhat.hacbs.container.analyser.deploy.DeployData;
import com.redhat.hacbs.container.analyser.deploy.Gav;

import io.quarkus.logging.Log;

public class ContainerRegistryDeployer {

    static {
        if (System.getProperty("jib.httpTimeout") == null) {
            //long timeout, but not infinite
            long fiveMinutes = TimeUnit.MINUTES.toMillis(5);
            System.setProperty("jib.httpTimeout", String.valueOf(fiveMinutes));
        }
    }

    private final String host;
    private final int port;
    private final String owner;
    private final String repository;
    private final boolean insecure;
    private final String prependTag;

    private final Credential credential;

    final String imageId;

    public ContainerRegistryDeployer(
            String host,
            int port,
            String owner,
            String token,
            String repository,
            boolean insecure,
            String prependTag, String imageId) {
        if (insecure) {
            System.setProperty("sendCredentialsOverHttp", "true");
        }

        this.host = host;
        this.port = port;
        this.owner = owner;
        this.repository = repository;
        this.insecure = insecure;
        this.prependTag = prependTag;
        this.imageId = imageId;
        String fullName = host + (port == 443 ? "" : ":" + port) + "/" + owner + "/" + repository;
        this.credential = ContainerUtil.processToken(fullName, token);

        Log.infof("Using username '%s' to publish to %s/%s/%s",
                (credential == null ? "" : credential.getUsername()),
                host, owner, repository);
        Log.infof("Prepend tag is %s", prependTag);
    }

    public void deployArchive(Path deployDir, Path sourcePath, Path logsPath, Set<String> gavs,
            BiConsumer<String, String> imageNameHashCallback) throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);

        // Read the tar to get the gavs and files
        DeployData imageData = new DeployData(deployDir, gavs);

        // Create the image layers
        createImages(imageData, sourcePath, logsPath, imageNameHashCallback);
    }

    public void tagArchive(List<String> gavNames) throws Exception {
        if (gavNames.isEmpty()) {
            throw new RuntimeException("Empty GAV list");
        }

        Deque<Gav> gavs = new ArrayDeque<>();
        for (var i : gavNames) {
            gavs.push(Gav.parse(i));
        }
        Gav first = gavs.pop();
        String existingImage = createImageName(imageId);
        RegistryImage existingRegistryImage = RegistryImage.named(existingImage);
        RegistryImage registryImage = RegistryImage.named(createImageName(first.getTag()));
        if (credential != null) {
            registryImage = registryImage.addCredentialRetriever(() -> Optional.of(credential));
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);

        JibContainerBuilder containerBuilder = Jib.from(existingRegistryImage)
                .setFormat(ImageFormat.OCI);

        Log.infof("Deploying image with tag %s", first.getTag());
        for (Gav gav : gavs) {
            Log.infof("Deploying image with tag %s", gav.getTag());
            containerizer = containerizer.withAdditionalTag(gav.getTag());
        }
        containerBuilder.addLabel("io.jvmbuildservice.gavs", String.join(",", gavNames));
        containerBuilder.containerize(containerizer);
    }

    public void deployPreBuildImage(String baseImage, Path sourcePath, String imageSourcePath, String tag)
            throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);
        String imageName = createImageName(tag);
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (credential != null) {
            registryImage = registryImage.addCredentialRetriever(() -> Optional.of(credential));
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying pre build image %s", imageName);

        JibContainerBuilder containerBuilder = Jib.from(baseImage)
                .setFormat(ImageFormat.OCI)
                .addLabel("quay.expires-after", "24h"); //we don't want to keep these around forever, they are an intermediate step

        var pathInContainer = AbsoluteUnixPath.get(imageSourcePath);
        try (Stream<Path> list = Files.list(sourcePath)) {
            var files = list.toList();
            FileEntriesLayer.Builder layerConfigurationBuilder = FileEntriesLayer.builder();
            for (Path file : files) {
                layerConfigurationBuilder.addEntryRecursive(
                        file, pathInContainer.resolve(file.getFileName()), new FilePermissionsProvider() {
                            @Override
                            public FilePermissions get(Path sourcePath, AbsoluteUnixPath destinationPath) {
                                try {
                                    return FilePermissions.fromPosixFilePermissions(Files.getPosixFilePermissions(sourcePath));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
            }

            containerBuilder.addFileEntriesLayer(layerConfigurationBuilder.build());
            Log.debugf("Image %s created", imageName);
            containerBuilder.containerize(containerizer);
        }
    }

    public void deployHermeticPreBuildImage(String baseImage, Path buildArtifactsPath, Path repositoryPath,
            String imageSourcePath, String tag) throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);
        String imageName = createImageName(tag);
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (credential != null) {
            registryImage = registryImage.addCredentialRetriever(() -> Optional.of(credential));
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying hermetic pre build image %s", imageName);

        JibContainerBuilder containerBuilder = Jib.from(baseImage)
                .setFormat(ImageFormat.OCI);

        FileEntriesLayer.Builder layerConfigurationBuilder = FileEntriesLayer.builder();
        var pathInContainer = AbsoluteUnixPath.get(imageSourcePath);
        Files.walkFileTree(repositoryPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("_remote.repositories")) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = repositoryPath.relativize(file).toString();
                if (Files.exists(buildArtifactsPath.resolve(relative))) {
                    return FileVisitResult.CONTINUE;
                }
                layerConfigurationBuilder.addEntry(file, pathInContainer.resolve(relative),
                        FilePermissions.fromPosixFilePermissions(Files.getPosixFilePermissions(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        containerBuilder.addFileEntriesLayer(layerConfigurationBuilder.build());
        Log.debugf("Image %s created", imageName);
        containerBuilder.containerize(containerizer);
    }

    private void createImages(DeployData imageData, Path sourcePath, Path logsPath,
            BiConsumer<String, String> imageNameHashCallback)
            throws InvalidImageReferenceException, InterruptedException, RegistryException, IOException,
            CacheDirectoryCreationException, ExecutionException {

        String imageName = createImageName();
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (credential != null) {
            registryImage = registryImage.addCredentialRetriever(() -> Optional.of(credential));
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying base image %s", imageName);

        Set<Gav> gavs = imageData.getGavs();

        AbsoluteUnixPath imageRoot = AbsoluteUnixPath.get("/");
        JibContainerBuilder containerBuilder = Jib.fromScratch()
                .setFormat(ImageFormat.OCI)
                .addLabel("groupId", imageData.getGroupIds())
                .addLabel("version", imageData.getVersions())
                .addLabel("artifactId", imageData.getArtifactIds());

        containerBuilder.addLabel("io.jvmbuildservice.gavs",
                gavs.stream().map(Gav::stringForm).collect(Collectors.joining(",")));
        List<Path> layers = getLayers(imageData.getArtifactsPath(), sourcePath, logsPath);
        for (Path layer : layers) {
            containerBuilder = containerBuilder.addLayer(List.of(layer), imageRoot);
        }

        Log.debugf("Image %s created", imageName);

        var result = containerBuilder.containerize(containerizer);

        if (imageNameHashCallback != null) {
            imageNameHashCallback.accept(imageName, result.getDigest().getHash());
        }
    }

    private String createImageName() {
        String tag = imageId == null ? UUID.randomUUID().toString() : imageId;
        return createImageName(tag);
    }

    private String createImageName(String tag) {
        // As the tests utilise prependTag for uniqueness so check for that
        // here to avoid reusing images when we want differentiation.
        if (!prependTag.isBlank()) {
            tag = prependTag + "_" + tag;
        }
        // Docker tag maximum size is 128
        // https://docs.docker.com/engine/reference/commandline/tag/
        if (tag.length() > 128) {
            tag = tag.substring(0, 128);
        }
        if (port == 443) {
            return host + "/" + owner + "/" + repository
                    + ":" + tag;
        }
        return host + ":" + port + "/" + owner + "/" + repository
                + ":" + tag;
    }

    private List<Path> getLayers(Path artifacts, Path source, Path logs) {
        Log.debug("\n Container details:\n"
                + "\t layer 1 (source) " + source.toString() + "\n"
                + "\t layer 2 (logs) " + logs.toString() + "\n"
                + "\t layer 3 (artifacts) " + artifacts.toString());

        return List.of(source, logs, artifacts);
    }

}
