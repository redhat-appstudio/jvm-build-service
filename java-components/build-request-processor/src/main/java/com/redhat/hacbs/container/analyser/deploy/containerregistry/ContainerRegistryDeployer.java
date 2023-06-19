package com.redhat.hacbs.container.analyser.deploy.containerregistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

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
import com.redhat.hacbs.container.analyser.deploy.Gav;
import com.redhat.hacbs.recipies.util.FileUtil;

import io.quarkus.logging.Log;

public class ContainerRegistryDeployer implements Deployer {

    static {
        if (System.getProperty("jib.httpTimeout") == null) {
            //long timeout, but not infinite
            long fiveMinutes = TimeUnit.MINUTES.toMillis(5);
            System.setProperty("jib.httpTimeout", "" + fiveMinutes);
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

    private final BiConsumer<String, String> imageNameHashCallback;

    static final ObjectMapper MAPPER = new ObjectMapper();

    public ContainerRegistryDeployer(
            String host,
            int port,
            String owner,
            String token,
            String repository,
            boolean insecure,
            String prependTag, BiConsumer<String, String> imageNameHashCallback) {
        if (insecure) {
            System.setProperty("sendCredentialsOverHttp", "true");
        }

        this.host = host;
        this.port = port;
        this.owner = owner;
        this.repository = repository;
        this.insecure = insecure;
        this.prependTag = prependTag;
        this.imageNameHashCallback = imageNameHashCallback;
        String fullName = host + (port == 443 ? "" : ":" + port) + "/" + owner + "/" + repository;
        if (!token.isBlank()) {
            if (token.trim().startsWith("{")) {
                //we assume this is a .dockerconfig file
                try (var parser = MAPPER.createParser(token)) {
                    DockerConfig config = parser.readValueAs(DockerConfig.class);
                    boolean found = false;
                    String tmpUser = null;
                    String tmpPw = null;
                    for (var i : config.getAuths().entrySet()) {
                        if (fullName.startsWith(i.getKey())) {
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
    public void deployArchive(Path deployDir, Path sourcePath, Path logsPath, Set<String> gavs) throws Exception {
        Log.debugf("Using Container registry %s:%d/%s/%s", host, port, owner, repository);

        // Read the tar to get the gavs and files
        DeployData imageData = new DeployData(deployDir, gavs, prependTag);

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

        AbsoluteUnixPath imageRoot = AbsoluteUnixPath.get("/");
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

        var result = containerBuilder.containerize(containerizer);

        if (imageNameHashCallback != null) {
            imageNameHashCallback.accept(imageName, result.getDigest().getHash());
        }
    }

    private String createImageName() {
        String imageName = UUID.randomUUID().toString();
        if (port == 443) {
            return host + "/" + owner + "/" + repository
                    + ":" + imageName;
        }
        return host + ":" + port + "/" + owner + "/" + repository
                + ":" + imageName;
    }

    private List<Path> getLayers(Path artifacts, Path source, Path logs) {
        Log.debug("\n Container details:\n"
                + "\t layer 1 (source) " + source.toString() + "\n"
                + "\t layer 2 (logs) " + logs.toString() + "\n"
                + "\t layer 3 (artifacts) " + artifacts.toString());

        return List.of(source, logs, artifacts);
    }
}
