package com.redhat.hacbs.common.images.ociclient;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryUnauthorizedException;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.http.ResponseException;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ManifestTemplate;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;

/**
 * An abstraction over the Jib client to make it easy to deal with images that contain Java artifacts.
 *
 *
 */
public class OCIRegistryClient {
    private static final String OCI_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";

    static final ObjectMapper MAPPER = new ObjectMapper();
    private final String registry;
    private final String owner;
    private final String repository;
    private final boolean enableHttpAndInsecureFailover;
    private final Credential credential;

    private static final Logger log = Logger.getLogger(OCIRegistryClient.class);

    public OCIRegistryClient(String registry,
            String owner,
            String repository,
            Optional<String> authToken,
            boolean enableHttpAndInsecureFailover) {
        this.registry = registry;
        this.owner = owner;
        this.repository = repository;
        this.enableHttpAndInsecureFailover = enableHttpAndInsecureFailover;
        if (enableHttpAndInsecureFailover) {
            System.setProperty("sendCredentialsOverHttp", "true");
        }

        if (authToken.isPresent() && !authToken.get().isBlank()) {
            if (authToken.get().trim().startsWith("{")) {
                //we assume this is a .dockerconfig file
                try (var parser = MAPPER.createParser(authToken.get())) {
                    DockerConfig config = parser.readValueAs(DockerConfig.class);
                    boolean found = false;
                    String tmpUser = null;
                    String tmpPw = null;
                    String host = null;
                    String fullName = registry + "/" + owner + "/" + repository;
                    for (var i : config.getAuths().entrySet()) {
                        if (fullName.startsWith(i.getKey())) {
                            found = true;
                            var decodedAuth = new String(Base64.getDecoder().decode(i.getValue().getAuth()),
                                    StandardCharsets.UTF_8);
                            int pos = decodedAuth.indexOf(":");
                            tmpUser = decodedAuth.substring(0, pos);
                            tmpPw = decodedAuth.substring(pos + 1);
                            host = i.getKey();
                            break;
                        }
                    }
                    if (!found) {
                        throw new RuntimeException("Unable to find a host matching " + registry
                                + " in provided dockerconfig, hosts provided: " + config.getAuths().keySet());
                    }
                    credential = Credential.from(tmpUser, tmpPw);
                    log.infof("Credential provided as .dockerconfig, selected host %s for registry %s", host, registry);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                var decoded = new String(Base64.getDecoder().decode(authToken.get()), StandardCharsets.UTF_8);
                int pos = decoded.indexOf(":");
                credential = Credential.from(decoded.substring(0, pos), decoded.substring(pos + 1));
                log.infof("Credential provided as base64 encoded token");
            }
        } else {
            credential = null;
            log.infof("No credential provided");
        }
    }

    public String getName() {
        return registry;
    }

    public Optional<LocalImage> pullImage(String tagOrDigest) {
        try {
            RegistryClient registryClient = getRegistryClient();
            try {
                return Optional.of(pullInternal(tagOrDigest, registryClient));
            } catch (RegistryUnauthorizedException e) {
                //this is quay specific possibly?
                //unfortunately we can't get the actual header
                String wwwAuthenticate = "Bearer realm=\"https://" + registry + "/v2/auth\",service=\"" + registry
                        + "\",scope=\"repository:" + owner + "/" + repository + ":pull\"";
                registryClient.authPullByWwwAuthenticate(wwwAuthenticate);
                return Optional.of(pullInternal(tagOrDigest, registryClient));
            }
        } catch (Exception ex) {
            Throwable cause = ex;
            while (cause != null) {
                if (cause instanceof ResponseException) {
                    ResponseException e = (ResponseException) cause;
                    if (e.getStatusCode() == 404) {
                        log.warnf("Failed to find image %s/%s/%s", registry, repository, tagOrDigest);
                        return Optional.empty();
                    }
                }
                cause = cause.getCause();
            }
            throw new RuntimeException(ex);
        }
    }

    private LocalImage pullInternal(String tagOrDigest, RegistryClient registryClient) throws IOException, RegistryException {
        ManifestAndDigest<ManifestTemplate> manifestAndDigest = registryClient.pullManifest(tagOrDigest,
                ManifestTemplate.class);

        ManifestTemplate manifest = manifestAndDigest.getManifest();
        DescriptorDigest descriptorDigest = manifestAndDigest.getDigest();

        String digestHash = descriptorDigest.getHash();
        String manifestMediaType = manifest.getManifestMediaType();

        if (OCI_MEDIA_TYPE.equalsIgnoreCase(manifestMediaType)) {
            return new LocalImageImpl(registryClient, (OciManifestTemplate) manifest, descriptorDigest, digestHash);
        } else {
            // TODO: handle docker type?
            // application/vnd.docker.distribution.manifest.v2+json = V22ManifestTemplate
            throw new RuntimeException(
                    "Wrong ManifestMediaType type. We support " + OCI_MEDIA_TYPE + ", but got " + manifestMediaType);
        }
    }

    private RegistryClient getRegistryClient() {
        RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(), registry,
                owner + "/" + repository,
                new FailoverHttpClient(enableHttpAndInsecureFailover, enableHttpAndInsecureFailover,
                        s -> log.info(s.getMessage())));

        if (credential != null) {
            factory.setCredential(credential);
        }

        return factory.newRegistryClient();
    }

    private void extractTarArchive(InputStream tarInput, String folder) throws IOException {
        try (GZIPInputStream inputStream = new GZIPInputStream(tarInput);
                TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream)) {

            for (TarArchiveEntry entry = tarArchiveInputStream.getNextEntry(); entry != null; entry = tarArchiveInputStream
                    .getNextEntry()) {
                extractEntry(entry, tarArchiveInputStream, folder);
            }
        }
    }

    private void extractEntry(ArchiveEntry entry, InputStream tar, String folder) throws IOException {
        final int bufferSize = 4096;
        final String path = folder + File.separator + entry.getName();
        if (entry.isDirectory()) {
            new File(path).mkdirs();
        } else {
            int count;
            byte[] data = new byte[bufferSize];
            try (FileOutputStream os = new FileOutputStream(path);
                    BufferedOutputStream dest = new BufferedOutputStream(os, bufferSize)) {
                while ((count = tar.read(data, 0, bufferSize)) != -1) {
                    dest.write(data, 0, count);
                }
            }
        }
    }

    /**
     * A local representation of a downloaded image.
     *
     */
    public class LocalImageImpl implements LocalImage {

        private final RegistryClient registryClient;
        private final OciManifestTemplate manifest;
        private final DescriptorDigest descriptorDigest;

        private final String digestHash;

        LocalImageImpl(RegistryClient registryClient, OciManifestTemplate manifest, DescriptorDigest descriptorDigest,
                String digestHash) {
            this.registryClient = registryClient;
            this.manifest = manifest;
            this.descriptorDigest = descriptorDigest;
            this.digestHash = digestHash;
        }

        @Override
        public int getLayerCount() {
            return manifest.getLayers().size();
        }

        @Override
        public OciManifestTemplate getManifest() {
            return manifest;
        }

        @Override
        public DescriptorDigest getDescriptorDigest() {
            return descriptorDigest;
        }

        @Override
        public String getDigestHash() {
            return digestHash;
        }

        @Override
        public void pullLayer(int layer, Path target) throws IOException {
            pullLayer(layer, target, s -> {
            }, s -> {
            });
        }

        @Override
        public void pullLayer(int layer,
                Path outputPath,
                Consumer<Long> blobSizeListener,
                Consumer<Long> writtenByteCountListener) throws IOException {
            BuildableManifestTemplate.ContentDescriptorTemplate artifactsLayer = manifest.getLayers().get(layer);

            Blob blob = registryClient.pullBlob(artifactsLayer.getDigest(), blobSizeListener, writtenByteCountListener);

            Path tarFile = Files.createFile(outputPath.resolve(digestHash + ".tar"));
            try (OutputStream tarOutputStream = Files.newOutputStream(tarFile)) {
                blob.writeTo(tarOutputStream);
            }
            try (InputStream tarInput = Files.newInputStream(tarFile)) {
                extractTarArchive(tarInput, outputPath.toString());
            }
        }

    }

}
