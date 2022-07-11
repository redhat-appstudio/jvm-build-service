package com.redhat.hacbs.artifactcache.services.client.ociregistry;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

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
    private final boolean enableHttpAndInsecureFailover;

    // TODO: We can also use the digestHash as the temp root folder then we do not need this ?
    private final Map<String, Path> imageCache = new ConcurrentHashMap<>();

    public OCIRegistryRepositoryClient(String registry, String owner, boolean enableHttpAndInsecureFailover) {
        this.registry = registry;
        this.owner = owner;
        this.enableHttpAndInsecureFailover = enableHttpAndInsecureFailover;
    }

    @Override
    public Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
            String target) {
        long time = System.currentTimeMillis();
        String groupPath = group.replace(DOT, File.separator);
        String hashedGav = ShaUtil.sha256sum(group, artifact, version);

        RegistryClient registryClient = getRegistryClient(registry, owner, group, hashedGav,
                enableHttpAndInsecureFailover);

        try {

            ManifestAndDigest<ManifestTemplate> manifestAndDigest = registryClient.pullManifest(hashedGav,
                    ManifestTemplate.class);

            ManifestTemplate manifest = manifestAndDigest.getManifest();
            DescriptorDigest descriptorDigest = manifestAndDigest.getDigest();

            String digestHash = descriptorDigest.getHash();

            Path repoRoot = getLocalCachePath(registryClient, manifest, digestHash);
            Path fileWeAreAfter = repoRoot.resolve(groupPath).resolve(artifact).resolve(version).resolve(target);

            boolean exists = Files.exists(fileWeAreAfter);
            if (exists) {
                byte[] contentWeAreAfter = Files.readAllBytes(fileWeAreAfter);
                ByteArrayInputStream bais = new ByteArrayInputStream(contentWeAreAfter);
                return Optional.of(new RepositoryResult(bais, Files.size(fileWeAreAfter), getSha1(fileWeAreAfter), Map.of()));
            } else {
                Log.warnf("Key %s:%s:%s not found", group, artifact, version);
            }

        } catch (IOException | RegistryException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            Log.warnf("OCI registry request to %s:%s:%s took %sms", group, artifact, version,
                    System.currentTimeMillis() - time);
        }

        return Optional.empty();
    }

    @Override
    public Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target) {
        return Optional.empty();
    }

    private RegistryClient getRegistryClient(String registry, String owner, String group, String hashedGav,
            boolean enableHttpAndInsecureFailover) {
        ImageReference imageReference = ImageReference.of(registry, owner + File.separator + group, hashedGav);
        CredentialRetrieverFactory credentialRetrieverFactory = CredentialRetrieverFactory.forImage(imageReference,
                (s) -> Log.info(s.getMessage()));

        RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(), registry,
                owner + File.separator + group,
                new FailoverHttpClient(enableHttpAndInsecureFailover, false, s -> Log.info(s.getMessage())));

        try {
            Optional<Credential> credential = credentialRetrieverFactory.dockerConfig().retrieve();
            if (credential.isPresent()) {
                factory.setCredential(credential.get());
            } else {
                Log.warn("No credential found for " + registry + ", proceeding without any");
            }
        } catch (CredentialRetrievalException cre) {
            throw new RuntimeException(cre);
        }

        RegistryClient registryClient = factory.newRegistryClient();

        return registryClient;
    }

    private Path getLocalCachePath(RegistryClient registryClient, ManifestTemplate manifest, String digestHash)
            throws IOException {
        if (imageCache.containsKey(digestHash)) {
            return pullStale(registryClient, manifest, digestHash);
        } else {
            return pullFreshAndCache(registryClient, manifest, digestHash);
        }
    }

    private Path pullStale(RegistryClient registryClient, ManifestTemplate manifest, String digestHash) throws IOException {
        Path path = imageCache.get(digestHash);
        if (Files.exists(path)) {
            return path;
        } else {
            return pullFreshAndCache(registryClient, manifest, digestHash);
        }

    }

    private Path pullFreshAndCache(RegistryClient registryClient, ManifestTemplate manifest, String digestHash)
            throws IOException {

        String manifestMediaType = manifest.getManifestMediaType();

        if (OCI_MEDIA_TYPE.equalsIgnoreCase(manifestMediaType)) {
            List<BuildableManifestTemplate.ContentDescriptorTemplate> layers = ((OciManifestTemplate) manifest).getLayers();

            // Layer 2 is artifacts
            BuildableManifestTemplate.ContentDescriptorTemplate artifactsLayer = layers.get(2);

            Blob blob = registryClient.pullBlob(artifactsLayer.getDigest(), s -> {
            }, s -> {
            });

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            blob.writeTo(out);

            // Extract to temp (TODO: Configure this ?)
            String osTmpDir = System.getProperty(SYS_PROP_TEMP_DIR);
            Path tempFullPath = Paths.get(osTmpDir, HACBS);
            Path outputPath = Files.createDirectories(tempFullPath);
            extractTarArchive(out.toByteArray(), outputPath.toString());
            Path repoRoot = Paths.get(outputPath.toString(), ARTIFACTS);

            imageCache.put(digestHash, repoRoot);

            return repoRoot;
        } else {
            // TODO: handle docker type?
            // application/vnd.docker.distribution.manifest.v2+json = V22ManifestTemplate
            throw new RuntimeException(
                    "Wrong ManifestMediaType type. We support " + OCI_MEDIA_TYPE + ", but got " + manifestMediaType);
        }
    }

    private Optional<String> getSha1(Path file) throws IOException {
        Path shaFile = Paths.get(file.toString() + DOT + SHA_1);
        boolean exists = Files.exists(shaFile);
        if (exists) {
            return Optional.of(Files.readString(shaFile));
        }
        return Optional.empty();
    }

    private void extractTarArchive(byte[] filecontents, String folder) throws IOException {
        try (
                GZIPInputStream inputStream = new GZIPInputStream(new ByteArrayInputStream(filecontents));
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

    private static final String SYS_PROP_TEMP_DIR = "java.io.tmpdir";
    private static final String HACBS = "hacbs";
    private static final String ARTIFACTS = "artifacts";
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";
    private static final String OCI_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";
}
