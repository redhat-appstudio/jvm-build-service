package com.redhat.hacbs.artifactcache.services.client.dockerregistry;

import com.google.cloud.tools.jib.api.DescriptorDigest;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException;
import com.redhat.hacbs.artifactcache.services.RepositoryClient;

import io.quarkus.logging.Log;
import java.util.concurrent.ConcurrentHashMap;

public class DockerRegistryRepositoryClient implements RepositoryClient {

    private final String registry;
    private final String owner;

    // TODO: We can also use the digestHash as the temp root folder then we do not need this ?
    private Map<String,Path> imageCache = new ConcurrentHashMap<>();
    
    public DockerRegistryRepositoryClient(String registry, String owner) {
        this.registry = registry;
        this.owner = owner;
    }

    @Override
    public Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
            String target) {
        long time = System.currentTimeMillis();
        String groupPath = group.replace(DOT, File.separator);
        String gav = String.format(GAV_FORMAT, group, artifact, version);
        String hashedGav = sha256sum(gav);

        RegistryClient registryClient = getRegistryClient(group, hashedGav);

        try {
            
            ManifestAndDigest<OciManifestTemplate> manifestAndDigest = registryClient.pullManifest(hashedGav,
                    OciManifestTemplate.class);

            OciManifestTemplate manifest = manifestAndDigest.getManifest();
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
                Log.tracef("Key %s not found", gav);
            }

        } catch (IOException | RegistryException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            Log.warnf("Docker registry request to %s took %sms", gav, System.currentTimeMillis() - time);
        }

        return Optional.empty();
    }

    @Override
    public Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target) {
        return Optional.empty();
    }

    private Path getLocalCachePath(RegistryClient registryClient, OciManifestTemplate manifest, String digestHash) throws IOException{
        if(imageCache.containsKey(digestHash)){
            return pullStale(registryClient, manifest, digestHash);
        }else{
            return pullFreshAndCache(registryClient, manifest, digestHash);
        }
    }
    
    private Path pullStale(RegistryClient registryClient, OciManifestTemplate manifest, String digestHash) throws IOException{
        Path path = imageCache.get(digestHash);
        if(Files.exists(path)){
            return path;
        }else{
            return pullFreshAndCache(registryClient, manifest, digestHash);
        }     
        
    }
    
    private Path pullFreshAndCache(RegistryClient registryClient, OciManifestTemplate manifest, String digestHash) throws IOException{
        List<BuildableManifestTemplate.ContentDescriptorTemplate> layers = manifest.getLayers();

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
    }
    
    private Optional<String> getSha1(Path file) throws IOException {
        Path shaFile = Paths.get(file.toString() + DOT + SHA_1);
        boolean exists = Files.exists(shaFile);
        if (exists) {
            return Optional.of(Files.readString(shaFile));
        }
        return Optional.empty();
    }

    private String sha256sum(String gav) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hashedGav = digest.digest(gav.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder(2 * hashedGav.length);
            for (int i = 0; i < hashedGav.length; i++) {
                String hex = Integer.toHexString(0xff & hashedGav[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
    }

    private RegistryClient getRegistryClient(String group, String hashedGav) {
        try {
            ImageReference imageReference = ImageReference.of(registry, owner + File.separator + group, hashedGav);
            CredentialRetrieverFactory credentialRetrieverFactory = CredentialRetrieverFactory.forImage(imageReference,
                    (s) -> System.out.println(s.getMessage()));

            RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(), registry,
                    owner + File.separator + group,
                    new FailoverHttpClient(false, false, s -> System.out.println(s.getMessage())));
            factory.setCredential(credentialRetrieverFactory.dockerConfig().retrieve().get());
            return factory.newRegistryClient();
        } catch (CredentialRetrievalException ex) {
            throw new RuntimeException(ex);
        }
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
    private static final String GAV_FORMAT = "%s:%s:%s";
    private static final String SHA_256 = "SHA-256";
    private static final String SHA_1 = "sha1";

}
