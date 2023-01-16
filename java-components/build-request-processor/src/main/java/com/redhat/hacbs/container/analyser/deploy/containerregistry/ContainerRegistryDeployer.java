package com.redhat.hacbs.container.analyser.deploy.containerregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import com.redhat.hacbs.container.analyser.deploy.DeployData;
import com.redhat.hacbs.container.analyser.deploy.Deployer;
import com.redhat.hacbs.container.analyser.deploy.DeployerUtil;
import com.redhat.hacbs.container.analyser.deploy.Gav;
import com.redhat.hacbs.container.analyser.util.FileUtil;
import io.quarkus.logging.Log;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

public class ContainerRegistryDeployer implements Deployer {

    static {
        if (System.getProperty("jib.httpTimeout") == null) {
            System.setProperty("jib.httpTimeout", "0");
        }
    }

    private final String host;
    private final int port;
    private final String owner;
    private final String repository;
    private final boolean insecure;
    private final String prependTag;

    private final String username;
    private final String password;

    private final Consumer<String> imageNameCallback;

    static final ObjectMapper MAPPER = new ObjectMapper();

    public ContainerRegistryDeployer(
        String host,
        int port,
        String owner,
        String token,
        String repository,
        boolean insecure,
        String prependTag, Consumer<String> imageNameCallback) {

        this.host = host;
        this.port = port;
        this.owner = owner;
        this.repository = repository;
        this.insecure = insecure;
        this.prependTag = prependTag;
        this.imageNameCallback = imageNameCallback;
        if (!token.isBlank()) {
            if (token.trim().startsWith("{")) {
                //we assume this is a .dockerconfig file
                try (var parser = MAPPER.createParser(token)) {
                    DockerConfig config = parser.readValueAs(DockerConfig.class);
                    boolean found = false;
                    String tmpUser = null;
                    String tmpPw = null;
                    for (var i : config.getAuths().entrySet()) {
                        if (host.contains(i.getKey())) { //TODO: is contains enough?
                            found = true;
                            var decodedAuth = new String(Base64.getDecoder().decode(i.getValue().getAuth()),
                                StandardCharsets.UTF_8);
                            int pos = decodedAuth.indexOf(":");
                            tmpUser = decodedAuth.substring(0, pos);
                            tmpPw = decodedAuth.substring(pos + 1);
                            break;
                        }
                    }
                    if (!found) {
                        throw new RuntimeException("Unable to find a host matching " + host
                            + " in provided dockerconfig, hosts provided: " + config.getAuths().keySet());
                    }
                    username = tmpUser;
                    password = tmpPw;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                var decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
                int pos = decoded.indexOf(":");
                username = decoded.substring(0, pos);
                password = decoded.substring(pos + 1);
            }
        } else {
            Log.errorf("No token configured");
            username = null;
            password = null;
        }
        Log.infof("Using username %s to publish to %s/%s/%s", username, host, owner, repository);
        Log.infof("Prepend tag is %s", prependTag);

    }

    @Override
    public void deployArchive(Path tarGzFile, Path sourcePath, Path logsPath) throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);

        // Read the tar to get the gavs and files
        DeployData imageData = getImageData(tarGzFile);

        try {
            // Create the image layers
            createImages(imageData, sourcePath, logsPath);

        } finally {
            FileUtil.deleteRecursive(imageData.getArtifactsPath());
        }
    }

    private void createImages(DeployData imageData, Path sourcePath, Path logsPath)
        throws InvalidImageReferenceException, InterruptedException, RegistryException, IOException,
        CacheDirectoryCreationException, ExecutionException {

        String imageName = createImageName();
        if (imageNameCallback != null) {
            imageNameCallback.accept(imageName);
        }
        RegistryImage registryImage = RegistryImage.named(imageName);
        if (username != null) {
            registryImage = registryImage.addCredential(username, password);
        }
        Containerizer containerizer = Containerizer
            .to(registryImage)
            .setAllowInsecureRegistries(insecure);
        Log.infof("Deploying base image %s", imageName);

        Set<Gav> gavs = imageData.getGavs();

        for (Gav gav : gavs) {
            Log.infof("Deploying image with tag %s", gav.getTag());
            containerizer = containerizer.withAdditionalTag(gav.getTag());
        }

        AbsoluteUnixPath imageRoot = AbsoluteUnixPath.get(SLASH);
        JibContainerBuilder containerBuilder = Jib.fromScratch()
            .setFormat(ImageFormat.OCI)
            .addLabel("groupId", imageData.getGroupIds())
            .addLabel("version", imageData.getVersions())
            .addLabel("artifactId", imageData.getArtifactIds());

        List<Path> layers = getLayers(imageData.getArtifactsPath(), sourcePath, logsPath);
        for (Path layer : layers) {
            containerBuilder = containerBuilder.addLayer(List.of(layer), imageRoot);
        }

        Log.debugf("Image %s created", imageName);

        containerBuilder.containerize(containerizer);
    }

    private String createImageName() {
        String imageName = UUID.randomUUID().toString();
        return host + DOUBLE_POINT + port + SLASH + owner + SLASH + repository
            + DOUBLE_POINT + imageName;
    }

    private List<Path> getLayers(Path artifacts, Path source, Path logs)
        throws IOException {

        // TODO: For now we create dummy source and logs

        Log.debug("\n Container details:\n"
            + "\t layer 1 (source) " + source.toString() + "\n"
            + "\t layer 2 (logs) " + logs.toString() + "\n"
            + "\t layer 3 (artifacts) " + artifacts.toString());

        return List.of(source, logs, artifacts);
    }

    private DeployData getImageData(Path tarGzFile) throws IOException {
        Path outputPath = Paths.get(Files.createTempDirectory(HACBS).toString(), ARTIFACTS);
        Files.createDirectories(outputPath);

        try (InputStream tarInput = Files.newInputStream(tarGzFile)) {
            DeployData imageData = new DeployData(outputPath, extractTarArchive(tarInput, outputPath.toString()));
            return imageData;
        }
    }

    private Set<Gav> extractTarArchive(InputStream tarInput, String folder) throws IOException {

        Set<Gav> gavs = new HashSet<>();

        try (GZIPInputStream inputStream = new GZIPInputStream(tarInput);
             TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream)) {

            for (TarArchiveEntry entry = tarArchiveInputStream.getNextTarEntry(); entry != null; entry = tarArchiveInputStream
                .getNextTarEntry()) {
                Optional<Gav> maybeGav = extractEntry(entry, tarArchiveInputStream, folder);
                if (maybeGav.isPresent()) {
                    gavs.add(maybeGav.get());

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

            String tag = DeployerUtil.sha256sum(groupId, artifactId, version);
            if (!prependTag.isBlank()) {
                tag = prependTag + UNDERSCORE + tag;
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
