package com.redhat.hacbs.container.verifier;

import static org.apache.commons.io.FilenameUtils.normalize;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public class MavenUtils {
    public static final Pattern COORDINATE_PATTERN = Pattern.compile(
            "(?<groupId>[^: ]+):(?<artifactId>[^: ]+)(:(?<extension>[^: ]*)(:(?<classifier>[^: ]+))?)?:(?<version>[^: ]+)");

    public static final String MAVEN_COMPILER_PLUGIN_ID = "org.apache.maven.plugins:maven-compiler-plugin";

    public static final String SOURCE = "source";

    public static final String TARGET = "target";

    public static String gavToCoords(Gav gav) {
        var groupId = gav.getGroupId();
        var artifactId = gav.getArtifactId();
        var extension = gav.getExtension() != null ? ":" + gav.getExtension() : "";
        var classifier = gav.getClassifier() != null ? ":" + gav.getClassifier() : "";
        var version = gav.getVersion();
        return groupId + ":" + artifactId + extension + classifier + ":" + version;
    }

    public static Gav coordsToGav(String coords) {
        var matcher = COORDINATE_PATTERN.matcher(coords);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Bad maven coordinates: " + coords);
        }

        var groupId = matcher.group("groupId");
        var artifactId = matcher.group("artifactId");
        var extension = matcher.group("extension");
        var classifier = matcher.group("classifier");
        var version = matcher.group("version");
        return new Gav(groupId, artifactId, version, classifier, extension, null, null, null, false, null, false, null);
    }

    public static String pathToCoords(Path file) {
        var str = normalize(file.toString(), true);
        var gav = new M2GavCalculator().pathToGav(str);

        if (gav == null) {
            throw new RuntimeException("Could not convert " + file + " to a GAV");
        }

        return gavToCoords(gav);
    }

    public static Path coordsToPath(String coords) {
        var gav = coordsToGav(coords);
        return Path.of(new M2GavCalculator().gavToPath(gav));
    }

    public static Optional<Plugin> getPlugin(MavenProject project, String pluginId) {
        var plugin = (Plugin) null;
        var build = project.getBuild();

        if (build != null) {
            var plugins = build.getPluginsAsMap();
            plugin = plugins.get(pluginId);

            if (plugin == null) {
                var pluginManagement = build.getPluginManagement();

                if (pluginManagement != null) {
                    plugins = pluginManagement.getPluginsAsMap();
                    plugin = plugins.get(pluginId);
                }
            }
        }

        return Optional.ofNullable(plugin);
    }

    public static Optional<String> getPluginConfiguration(MavenProject project, String pluginId, String key) {
        var value = (String) null;
        var optPlugin = getPlugin(project, pluginId);

        if (optPlugin.isPresent()) {
            var plugin = optPlugin.get();
            var configuration = (Xpp3Dom) plugin.getConfiguration();

            if (configuration != null) {
                var child = configuration.getChild(key);

                if (child != null) {
                    var childValue = child.getValue();

                    if (!childValue.isBlank()) {
                        value = childValue;
                    }
                }
            }
        }

        return Optional.ofNullable(value);
    }

    public static Optional<String> getCompilerSource(Model model) {
        var project = new MavenProject(model);
        return getPluginConfiguration(project, MAVEN_COMPILER_PLUGIN_ID, SOURCE);
    }

    public static Optional<String> getCompilerTarget(Model model) {
        var project = new MavenProject(model);
        return getPluginConfiguration(project, MAVEN_COMPILER_PLUGIN_ID, TARGET);
    }
}
