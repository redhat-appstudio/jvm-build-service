package io.github.redhatappstudio.jvmbuild.cli.rebuilt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifactSpec;

class RebuiltDownloadCommandTest {

    @Test
    public void testImage(@TempDir Path dir)
            throws URISyntaxException, IOException {
        Path path = Paths.get(RebuiltDownloadCommand.class.getClassLoader().getResource("testImage.tar").toURI());

        File targetDirectory = new File(dir.toString(), "target");
        File temp = new File(dir.toString(), "temp");

        RebuiltArtifactSpec spec = new RebuiltArtifactSpec();
        spec.setGav("org.foo:foobar:1.0");
        RebuiltDownloadCommand rb = new RebuiltDownloadCommand();
        rb.selection = RebuiltDownloadCommand.DownloadSelection.ALL;
        rb.targetDirectory = targetDirectory;
        rb.extractTars(spec, path.toFile(), temp);

        Path logs = Paths.get(targetDirectory.toString(), "org.foo--foobar--1.0-logs.tar");
        Path source = Paths.get(targetDirectory.toString(), "org.foo--foobar--1.0-source.tar");

        assertTrue(Files.exists(logs));
        assertTrue(Files.exists(source));

        try (InputStream is = Files.newInputStream(logs)) {
            assertEquals("036c8cb37f17c46e03641e16444b234a", DigestUtils.md5Hex(is));
        }
        try (InputStream is = Files.newInputStream(source)) {
            assertEquals("f5892601dd69d6ddc4ede0ef9484a9c9", DigestUtils.md5Hex(is));
        }
    }
}
