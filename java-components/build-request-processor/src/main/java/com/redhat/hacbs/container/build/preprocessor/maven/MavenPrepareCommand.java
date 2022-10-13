package com.redhat.hacbs.container.build.preprocessor.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

import com.redhat.hacbs.container.build.preprocessor.AbstractPreprocessor;

import picocli.CommandLine;

/**
 * A simple preprocessor that attempts to fix problematic maven build files.
 * <p>
 * At present this is a no-op
 */
@CommandLine.Command(name = "maven-prepare")
public class MavenPrepareCommand extends AbstractPreprocessor {

    static final Set<PluginInfo> TO_REMOVE = Set.of(
            new PluginInfo("org.glassfish.copyright", "glassfish-copyright-maven-plugin"),
            new PluginInfo("org.sonatype.plugins", "nexus-staging-maven-plugin"),
            new PluginInfo("com.mycila", "license-maven-plugin"),
            new PluginInfo("de.jjohannes", "gradle-module-metadata-maven-plugin"));

    @Override
    public void run() {
        try {
            Files.walkFileTree(buildRoot, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    if (fileName.equals("pom.xml")) {
                        handleBuild(file, buildRoot.resolve("pom.xml").equals(file));
                    }
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void handleBuild(Path file, boolean topLevel) throws IOException {
        try (BufferedReader pomReader = Files.newBufferedReader(file)) {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(pomReader);

            boolean modified = false;
            if (model.getBuild() != null && model.getBuild().getPlugins() != null) {
                modified |= handlePlugins(model.getBuild().getPlugins(), false, topLevel);
            }
            if (model.getBuild() != null && model.getBuild().getPluginManagement() != null
                    && model.getBuild().getPluginManagement().getPlugins() != null) {
                modified |= handlePlugins(model.getBuild().getPluginManagement().getPlugins(), true, topLevel);
            }

            if (modified) {
                MavenXpp3Writer writer = new MavenXpp3Writer();
                try (var out = Files.newOutputStream(file)) {
                    writer.write(out, model);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean handlePlugins(List<Plugin> plugins, boolean pluginManagement, boolean topLevel) {
        boolean modified = false;
        for (Iterator<Plugin> iterator = plugins.iterator(); iterator.hasNext();) {
            Plugin i = iterator.next();
            PluginInfo p = new PluginInfo(i.getGroupId(), i.getArtifactId());
            if (TO_REMOVE.contains(p)) {
                iterator.remove();
                modified = true;
            } else if (i.getArtifactId().equals("maven-deploy-plugin")) {
                //we don't want top level deployment config
                //per artifact level is fine as some things like unit tests
                //should not be deployed
                if (topLevel && i.getConfiguration() != null) {
                    i.setConfiguration(null);
                    modified = true;
                }
            }
        }
        return modified;
    }

    record PluginInfo(String group, String artifact) {

    }
}
