package io.github.redhatappstudio.jvmbuild.cli.rebuilt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.redhat.hacbs.artifactcache.ContainerRegistryTestResourceManager;
import com.redhat.hacbs.artifactcache.services.ContainerRegistryStorageTest;
import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifactSpec;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTestResource(value = ContainerRegistryTestResourceManager.class, restrictToAnnotatedClass = true)
@QuarkusTest
public class RebuiltDownloadCommandTest {

    @Test
    public void testImage(@TempDir Path dir) throws IOException {
        var registry = ConfigProvider.getConfig().getValue("store.rebuilt.registry", String.class);
        RebuiltArtifactSpec spec = new RebuiltArtifactSpec();
        spec.setGav("org.foo:foobar:1.0");
        spec.setImage(registry + "/hacbs/artifact-deployments:" + ContainerRegistryStorageTest.VERSION);

        File targetDirectory = new File(dir.toString(), "target");
        targetDirectory.mkdirs();

        RebuiltDownloadCommand rb = new RebuiltDownloadCommand();
        rb.targetDirectory = targetDirectory;
        rb.downloadImage(spec);

        Path logs = Paths.get(targetDirectory.toString(), "org.foo--foobar--1.0-logs.tar.gz");
        Path source = Paths.get(targetDirectory.toString(), "org.foo--foobar--1.0-source.tar.gz");

        assertTrue(Files.exists(logs));
        assertTrue(Files.exists(source));

        extract(logs.toFile(), targetDirectory);
        extract(source.toFile(), targetDirectory);

        String pomDigest = DigestUtils.md5Hex(Files.newInputStream(Paths.get("pom.xml").toAbsolutePath()));
        try (InputStream is = Files.newInputStream(Paths.get(targetDirectory.toString(), "logs", "pom.xml"))) {
            assertEquals(pomDigest, DigestUtils.md5Hex(is));
        }
        String adocDigest = DigestUtils.md5Hex(Files.newInputStream(Paths.get("..", "..", "README.adoc").toAbsolutePath()));
        try (InputStream is = Files.newInputStream(Paths.get(targetDirectory.toString(), "source", "README.adoc"))) {
            assertEquals(adocDigest, DigestUtils.md5Hex(is));
        }
    }

    private void extract(File source, File destination)
            throws IOException {

        try (ArchiveInputStream input = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(source)))) {
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
}
