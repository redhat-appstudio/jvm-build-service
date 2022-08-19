package com.redhat.hacbs.artifactcache.services.client.ociregistry;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.ImageReference;
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
import com.redhat.hacbs.artifactcache.services.RepositoryClient;
import com.redhat.hacbs.artifactcache.services.client.ShaUtil;

import io.quarkus.logging.Log;

public class OCIRegistryRepositoryClient implements RepositoryClient {

    private final String registry;
    private final String owner;
    private final Optional<String> token;
    private final Optional<String> prependHashedGav;
    private final boolean enableHttpAndInsecureFailover;
    private final Path cacheRoot;

    public OCIRegistryRepositoryClient(String registry, String owner, Optional<String> token, Optional<String> prependHashedGav,
            boolean enableHttpAndInsecureFailover) {
        this.registry = registry;
        this.owner = owner;
        this.token = token;
        this.prependHashedGav = prependHashedGav;
        this.enableHttpAndInsecureFailover = enableHttpAndInsecureFailover;

        Config config = ConfigProvider.getConfig();
        Path cachePath = config.getValue("cache-path", Path.class);
        try {
            this.cacheRoot = Files.createDirectories(Paths.get(cachePath.toAbsolutePath().toString(), HACBS));
            Log.debugf(" Using [%s] as local cache folder", cacheRoot);
        } catch (IOException ex) {
            throw new RuntimeException("could not create cache directory", ex);
        }
    }

    @Override
    public Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
            String target) {
        long time = System.currentTimeMillis();
        String groupPath = group.replace(DOT, File.separator);
        String hashedGav = ShaUtil.sha256sum(group, artifact, version);
        if (prependHashedGav.isPresent()) {
            hashedGav = prependHashedGav.get() + UNDERSCORE + hashedGav;
        }
        if (hashedGav.length() > 128) {
            hashedGav = hashedGav.substring(0, 128);
        }

        RegistryClient registryClient = getRegistryClient(group, hashedGav);

        try {

            ManifestAndDigest<ManifestTemplate> manifestAndDigest = registryClient.pullManifest(hashedGav,
                    ManifestTemplate.class);

            ManifestTemplate manifest = manifestAndDigest.getManifest();
            DescriptorDigest descriptorDigest = manifestAndDigest.getDigest();

            String digestHash = descriptorDigest.getHash();
            Optional<Path> repoRoot = getLocalCachePath(registryClient, manifest, digestHash);
            if (repoRoot.isPresent()) {
                Path fileWeAreAfter = repoRoot.get().resolve(groupPath).resolve(artifact).resolve(version).resolve(target);

                boolean exists = Files.exists(fileWeAreAfter);
                if (exists) {
                    return Optional.of(
                            new RepositoryResult(Files.newInputStream(fileWeAreAfter), Files.size(fileWeAreAfter),
                                    getSha1(fileWeAreAfter),
                                    Map.of()));
                } else {
                    Log.warnf("Key %s:%s:%s not found", group, artifact, version);
                }
            }
        } catch (IOException | RegistryException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            Log.debugf("OCI registry request to %s:%s:%s took %sms", group, artifact, version,
                    System.currentTimeMillis() - time);
        }

        return Optional.empty();
    }

    @Override
    public Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target) {
        return Optional.empty();
    }

    private RegistryClient getRegistryClient(String group, String hashedGav) {

        RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(), registry,
                owner + File.separator + group,
                new FailoverHttpClient(enableHttpAndInsecureFailover, false, s -> Log.info(s.getMessage())));

        Optional<Credential> credential = getCredential(group, hashedGav);

        if (credential.isPresent()) {
            factory.setCredential(credential.get());
        } else {
            Log.debugf("No credential found for %s, proceeding without any", registry);
        }

        RegistryClient registryClient = factory.newRegistryClient();

        return registryClient;
    }

    private Optional<Credential> getCredential(String group, String hashedGav) {
        // If there is token configured for this, use that, else fallback to .docker/config.json
        if (token.isPresent()) {
            return Optional.of(Credential.from(Credential.OAUTH2_TOKEN_USER_NAME, token.get()));
        } else {
            ImageReference imageReference = ImageReference.of(registry, owner + File.separator + group, hashedGav);
            CredentialRetrieverFactory credentialRetrieverFactory = CredentialRetrieverFactory.forImage(imageReference,
                    (s) -> Log.debug(s.getMessage()));
            try {
                return credentialRetrieverFactory.dockerConfig().retrieve();
            } catch (CredentialRetrievalException cre) {
                throw new RuntimeException(cre);
            }
        }
    }

    private Optional<Path> getLocalCachePath(RegistryClient registryClient, ManifestTemplate manifest, String digestHash)
            throws IOException {
        Path digestHashPath = cacheRoot.resolve(digestHash);
        if (existInLocalCache(digestHashPath)) {
            return Optional.of(Paths.get(digestHashPath.toString(), ARTIFACTS));
        } else {
            return pullFromRemoteAndCache(registryClient, manifest, digestHash, digestHashPath);
        }
    }

    private Optional<Path> pullFromRemoteAndCache(RegistryClient registryClient, ManifestTemplate manifest, String digestHash,
            Path digestHashPath)
            throws IOException {

        String manifestMediaType = manifest.getManifestMediaType();

        if (OCI_MEDIA_TYPE.equalsIgnoreCase(manifestMediaType)) {
            List<BuildableManifestTemplate.ContentDescriptorTemplate> layers = ((OciManifestTemplate) manifest).getLayers();
            if (layers.size() == 3) {
                // Layer 2 is artifacts
                BuildableManifestTemplate.ContentDescriptorTemplate artifactsLayer = layers.get(2);

                Blob blob = registryClient.pullBlob(artifactsLayer.getDigest(), s -> {
                }, s -> {
                });

                Path outputPath = Files.createDirectories(digestHashPath);

                Path tarFile = Files.createFile(Paths.get(outputPath.toString(), digestHash + ".tar"));
                try (OutputStream tarOutputStream = Files.newOutputStream(tarFile)) {
                    blob.writeTo(tarOutputStream);
                }
                try (InputStream tarInput = Files.newInputStream(tarFile)) {
                    extractTarArchive(tarInput, outputPath.toString());
                    return Optional.of(Paths.get(outputPath.toString(), ARTIFACTS));
                }
            } else {
                Log.warnf("Unexpexted layer size %d. We expext 3", layers.size());
                return Optional.empty();
            }
        } else {
            // TODO: handle docker type?
            // application/vnd.docker.distribution.manifest.v2+json = V22ManifestTemplate
            throw new RuntimeException(
                    "Wrong ManifestMediaType type. We support " + OCI_MEDIA_TYPE + ", but got " + manifestMediaType);
        }
    }

    private boolean existInLocalCache(Path digestHashPath) {
        return Files.exists(digestHashPath) && Files.isDirectory(digestHashPath);
    }

    private Optional<String> getSha1(Path file) throws IOException {
        Path shaFile = Paths.get(file.toString() + DOT + SHA_1);
        boolean exists = Files.exists(shaFile);
        if (exists) {
            return Optional.of(Files.readString(shaFile));
        }
        return Optional.empty();
    }

    private void extractTarArchive(InputStream tarInput, String folder) throws IOException {
        try (GZIPInputStream inputStream = new GZIPInputStream(tarInput);
                TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream)) {

            for (TarArchiveEntry entry = tarArchiveInputStream.getNextTarEntry(); entry != null; entry = tarArchiveInputStream
                    .getNextTarEntry()) {
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

    private static final String UNDERSCORE = "_";
    private static final String HACBS = "hacbs";
    private static final String ARTIFACTS = "artifacts";
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";
    private static final String OCI_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";
}
