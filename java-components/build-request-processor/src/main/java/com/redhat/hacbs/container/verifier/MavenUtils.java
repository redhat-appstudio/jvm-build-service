package com.redhat.hacbs.container.verifier;

import static org.apache.commons.io.FilenameUtils.normalize;
import static org.apache.http.HttpStatus.SC_OK;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import com.redhat.hacbs.container.analyser.build.JavaVersion;
import com.redhat.hacbs.container.analyser.build.JdkVersion;

import io.quarkus.logging.Log;

public class MavenUtils {
    public static final Pattern COORDINATE_PATTERN = Pattern.compile(
            "(?<groupId>[^: ]+):(?<artifactId>[^: ]+)(:(?<extension>[^: ]*)(:(?<classifier>[^: ]+))?)?:(?<version>[^: ]+)");

    public static final Pattern JAVA_VERSION_PATTERN = Pattern.compile(
            "(?<major>[^. ]+)(.(?<minor>[^. ]+)(.(?<micro>[^._ ]+))?)?(_(?<update>[^- ]*)(-(?<identifier>[^ ]+))?)?");

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
        return new Gav(groupId, artifactId, version, classifier, extension != null ? extension : "jar", null, null, null, false,
                null, false, null);
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

    public static Optional<Path> downloadFile(URI uri) throws IOException {
        try (var client = HttpClientBuilder.create().build()) {
            Log.debugf("Getting URL %s", uri);
            var get = new HttpGet(uri);

            try (var response = client.execute(get)) {
                var statusLine = response.getStatusLine();
                var statusCode = statusLine.getStatusCode();

                if (statusCode != SC_OK) {
                    var reasonPhrase = statusLine.getReasonPhrase();
                    Log.errorf("Unexpected status code %d (%s) for %s", statusCode, reasonPhrase, uri);
                    return Optional.empty();
                }

                var tempDirectory = Files.createTempDirectory("verify-built-artifacts-");
                var outputFile = tempDirectory.resolve(Path.of(uri.getPath()).getFileName());
                Log.debugf("Saving to %s", outputFile);
                var entity = response.getEntity();
                var content = entity.getContent();
                Files.copy(content, outputFile);
                return Optional.of(outputFile);
            }
        }
    }

    public static Optional<Path> downloadCoordinates(String baseUrl, String coords) throws IOException {
        var path = coordsToPath(coords);
        var url = baseUrl + "/" + path + "?upstream-only=true";
        return downloadFile(URI.create(url).normalize());
    }

    public static Optional<JavaVersion> getBuildJdk(Path path) {
        try (var jar = new JarInputStream(Files.newInputStream(path))) {
            var manifest = jar.getManifest();
            var buildJdk = manifest.getMainAttributes().getValue("Build-Jdk");
            Log.debugf("Build-Jdk: %s", buildJdk);

            if (buildJdk == null) {
                buildJdk = manifest.getMainAttributes().getValue("Build-Jdk-Spec");
                Log.debugf("Build-Jdk-Spec: %s", buildJdk);
            }

            if (buildJdk == null) {
                return Optional.empty();
            }

            var matcher = JAVA_VERSION_PATTERN.matcher(buildJdk);

            Log.debugf("matches: " + matcher.matches());

            if (matcher.matches()) {
                var major = matcher.group("major");
                var minor = matcher.group("minor");
                var micro = matcher.group("micro");
                var update = matcher.group("update");
                var identifier = matcher.group("identifier");
                var jdkVersion = new JdkVersion(major, minor, micro, update, identifier);
                Log.debugf("%s", jdkVersion);

                if (!major.equals("1")) {
                    buildJdk = jdkVersion.major();
                } else {
                    if (minor == null) {
                        throw new IllegalArgumentException("Bad JDK version: " + buildJdk);
                    }

                    buildJdk = jdkVersion.minor();
                }
            } else {
                throw new IllegalArgumentException("Bad JDK version: " + buildJdk);
            }

            return Optional.of(new JavaVersion(buildJdk));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Optional<JavaVersion> getBuildJdk(String baseUrl, String coords) throws IOException {
        var optPath = downloadCoordinates(baseUrl, coords);
        return optPath.flatMap(MavenUtils::getBuildJdk);
    }
}
