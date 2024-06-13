package com.redhat.hacbs.container.deploy.containerregistry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FilePermissions;
import com.google.cloud.tools.jib.api.buildplan.FilePermissionsProvider;
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import com.redhat.hacbs.common.sbom.GAV;
import com.redhat.hacbs.container.deploy.DeployData;

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

    public ContainerRegistryDeployer(
            String host,
            int port,
            String owner,
            String token,
            String repository,
            boolean insecure,
            String prependTag) {
        if (insecure) {
            System.setProperty("sendCredentialsOverHttp", "true");
        }

        this.host = host;
        this.port = port;
        this.owner = owner;
        this.repository = repository;
        this.insecure = insecure;
        this.prependTag = prependTag;
        String fullName = host + (port == 443 ? "" : ":" + port) + "/" + owner + "/" + repository;
        this.credential = ContainerUtil.processToken(fullName, token);

        Log.infof("Using username '%s' to publish to %s/%s/%s",
                (credential == null ? "" : credential.getUsername()),
                host, owner, repository);
        Log.infof("Prepend tag is %s", prependTag);
    }

    @Deprecated
    public void deployArchive(Path deployDir, Path sourcePath, Path logsPath, Set<String> gavs, String imageId, String buildId,
            BiConsumer<String, String> imageNameHashCallback) throws Exception {
        // Read the tar to get the gavs and files
        DeployData imageData = new DeployData(deployDir, gavs);

        // Create the image layers
        createImages(imageData, sourcePath, logsPath, imageId, buildId, imageNameHashCallback);
    }

    public void tagArchive(String imageDigest, List<String> gavNames) throws Exception {
        if (gavNames.isEmpty()) {
            throw new RuntimeException("Empty GAV list");
        }

        Deque<GAV> gavs = new ArrayDeque<>();
        for (var i : gavNames) {
            gavs.push(GAV.parse(i));
        }
        GAV first = gavs.pop();
        String existingImage = createImageNameFromDigest(imageDigest);
        Log.warnf("### Using imageDigest %s and existingImage %s", imageDigest, existingImage);
        RegistryImage existingRegistryImage = RegistryImage.named(existingImage);
        RegistryImage registryImage = RegistryImage.named(createImageName(first.getTag()));
        Log.warnf("### Using createImageName %s", createImageName(first.getTag()));
        if (credential != null) {
            registryImage = registryImage.addCredentialRetriever(() -> Optional.of(credential));
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .addEventHandler(LogEvent.class, logEvent -> Log.infof(logEvent.getMessage()))
                .setAllowInsecureRegistries(insecure);

        JibContainerBuilder containerBuilder = Jib.from(existingRegistryImage)
                .setFormat(ImageFormat.OCI);

        Log.infof("Deploying image with tag %s (GAV: %s)", first.getTag(), first.stringForm());
        for (GAV gav : gavs) {
            Log.infof("Deploying image with tag %s (GAV: %s)", gav.getTag(), gav.stringForm());
            containerizer = containerizer.withAdditionalTag(gav.getTag());
        }
        containerBuilder.addLabel("io.jvmbuildservice.gavs", String.join(",", gavNames));
        containerBuilder.containerize(containerizer);
    }

    @Deprecated
    public void deployPreBuildImage(Path sourcePath, String imageSourcePath, String tag, BiConsumer<String, String> imageNameHashCallback)
            throws Exception {
        String imageName = createImageName(tag);
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (credential != null) {
            registryImage = registryImage.addCredentialRetriever(() -> Optional.of(credential));
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .addEventHandler(LogEvent.class, logEvent -> Log.infof(logEvent.getMessage()))
                .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying pre build image %s", imageName);

        // TODO: Change from to Jib.fromScratch(). Using micro to allow easy examination of pre-build-images
        JibContainerBuilder containerBuilder = Jib.from("registry.access.redhat.com/ubi8-micro:latest")
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
                                    return FilePermissions.fromPosixFilePermissions(
                                            Files.getPosixFilePermissions(sourcePath, LinkOption.NOFOLLOW_LINKS));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
            }

            containerBuilder.addFileEntriesLayer(layerConfigurationBuilder.build());
            var result = containerBuilder.containerize(containerizer);

            if (imageNameHashCallback != null) {
                imageNameHashCallback.accept(imageName, result.getDigest().getHash());
            }
        }
    }

    public void deployHermeticPreBuildImage(String baseImage, Path buildArtifactsPath, Path repositoryPath,
            String imageSourcePath, String tag,
            BiConsumer<String, String> imageNameHashCallback) throws Exception {
        String imageName = createImageName(tag);
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (credential != null) {
            registryImage = registryImage.addCredentialRetriever(() -> Optional.of(credential));
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .addEventHandler(LogEvent.class, logEvent -> Log.infof(logEvent.getMessage()))
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
                        FilePermissions
                                .fromPosixFilePermissions(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)));
                return FileVisitResult.CONTINUE;
            }
        });
        containerBuilder.addFileEntriesLayer(layerConfigurationBuilder.build());
        var result = containerBuilder.containerize(containerizer);

        if (imageNameHashCallback != null) {
            imageNameHashCallback.accept(imageName, result.getDigest().getHash());
        }
    }

    private void createImages(DeployData imageData, Path sourcePath, Path logsPath,
            String imageId, String buildId, BiConsumer<String, String> imageNameHashCallback)
            throws InvalidImageReferenceException, InterruptedException, RegistryException, IOException,
            CacheDirectoryCreationException, ExecutionException {

        String imageName = createImageName(buildId);
        Log.warnf("### Using buildId %s imageName %s", buildId, imageName);
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (credential != null) {
            registryImage = registryImage.addCredentialRetriever(() -> Optional.of(credential));
        }
        Containerizer containerizer = Containerizer
                .to(registryImage)
                .addEventHandler(LogEvent.class, logEvent -> Log.infof(logEvent.getMessage()))
                .withAdditionalTag(imageId)
                .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying base image %s", imageName);

        Set<GAV> gavs = imageData.getGavs();

        AbsoluteUnixPath imageRoot = AbsoluteUnixPath.get("/");
        JibContainerBuilder containerBuilder = Jib.fromScratch()
                .setFormat(ImageFormat.OCI)
                .addLabel("groupId", imageData.getGroupIds())
                .addLabel("version", imageData.getVersions())
                .addLabel("artifactId", imageData.getArtifactIds());

        containerBuilder.addLabel("io.jvmbuildservice.gavs",
                gavs.stream().map(GAV::stringForm).collect(Collectors.joining(",")));
        List<Path> layers = getLayers(imageData.getArtifactsPath(), sourcePath, logsPath);
        Log.infof("### Got layers path %s " , layers );
        for (Path layer : layers) {
            containerBuilder = containerBuilder.addLayer(List.of(layer), imageRoot);
        }

        var result = containerBuilder.containerize(containerizer);

        if (imageNameHashCallback != null) {
            imageNameHashCallback.accept(imageName, result.getDigest().getHash());
        }
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

    private String createImageNameFromDigest(String digest) {
        if (port == 443) {
            return host + "/" + owner + "/" + repository
                    + "@" + digest;
        }
        return host + ":" + port + "/" + owner + "/" + repository
                + "@" + digest;
    }

    private List<Path> getLayers(Path artifacts, Path source, Path logs) {
        Log.debug("\n Container details:\n"
                + "\t layer 1 (source) " + source.toString() + "\n"
                + "\t layer 2 (logs) " + logs.toString() + "\n"
                + "\t layer 3 (artifacts) " + artifacts.toString());

        return List.of(source, logs, artifacts);
    }

}
