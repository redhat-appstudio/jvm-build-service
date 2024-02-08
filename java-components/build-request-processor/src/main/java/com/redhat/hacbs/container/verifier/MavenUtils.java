package com.redhat.hacbs.container.verifier;

import static org.apache.commons.io.FilenameUtils.normalize;
import static org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor.DEFAULT_GLOBAL_SETTINGS_FILE;
import static org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor.DEFAULT_USER_SETTINGS_FILE;
import static org.apache.maven.settings.MavenSettingsBuilder.ALT_GLOBAL_SETTINGS_XML_LOCATION;
import static org.apache.maven.settings.MavenSettingsBuilder.ALT_USER_SETTINGS_XML_LOCATION;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.AuthenticationSelector;
import org.eclipse.aether.repository.MirrorSelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;

import io.quarkus.logging.Log;

public class MavenUtils {
    public static final String MAVEN_COMPILER_PLUGIN_ID = "org.apache.maven.plugins:maven-compiler-plugin";

    public static final String SOURCE = "source";

    public static final String TARGET = "target";

    public static Settings newSettings(Path globalSettingsFile, Path settingsFile) {
        try {
            var builderFactory = new DefaultSettingsBuilderFactory();
            var builder = builderFactory.newInstance();
            var request = new DefaultSettingsBuildingRequest();
            var altUserSettingsXmlLocation = System.getProperty(ALT_USER_SETTINGS_XML_LOCATION);
            var userSettingsFile = altUserSettingsXmlLocation != null ? Path.of(altUserSettingsXmlLocation)
                    : (settingsFile != null ? settingsFile : DEFAULT_USER_SETTINGS_FILE.toPath());
            Log.debugf("User settings file: %s", userSettingsFile);
            request.setUserSettingsFile(userSettingsFile.toAbsolutePath().toFile());
            var altGlobalSettingsXmlLocation = System.getProperty(ALT_GLOBAL_SETTINGS_XML_LOCATION);

            if (altGlobalSettingsXmlLocation != null) {
                globalSettingsFile = Path.of(altGlobalSettingsXmlLocation);
            } else if (globalSettingsFile == null) {
                var mavenConf = System.getProperty("maven.conf");

                if (mavenConf != null) {
                    globalSettingsFile = DEFAULT_GLOBAL_SETTINGS_FILE.toPath();
                }
            }

            Log.debugf("Global settings file: %s", globalSettingsFile);

            if (globalSettingsFile != null) {
                request.setGlobalSettingsFile(globalSettingsFile.toAbsolutePath().toFile());
            }

            request.setSystemProperties((Properties) System.getProperties().clone());
            var result = builder.build(request);
            return result.getEffectiveSettings();
        } catch (SettingsBuildingException e) {
            throw new RuntimeException(e);
        }
    }

    public static MirrorSelector newMirrorSelector(Settings settings) {
        var mirrors = settings.getMirrors();
        var selector = new DefaultMirrorSelector();
        mirrors.forEach(mirror -> {
            var id = mirror.getId();
            var url = mirror.getUrl();
            var layout = mirror.getLayout();
            var blocked = mirror.isBlocked();
            var mirrorOf = mirror.getMirrorOf();
            var mirrorOfLayouts = mirror.getMirrorOfLayouts();
            Log.debugf("Adding mirror: %s", mirror);
            selector.add(id, url, layout, false, blocked, mirrorOf, mirrorOfLayouts);
        });
        return selector;
    }

    public static AuthenticationSelector newAuthenticationSelector(Settings settings) {
        var selector = new DefaultAuthenticationSelector();
        var servers = settings.getServers();
        servers.forEach(server -> {
            var id = server.getId();
            var username = server.getUsername();
            var password = server.getPassword();
            var privateKey = server.getPrivateKey();
            var passphrase = server.getPassphrase();
            var builder = new AuthenticationBuilder();
            builder.addUsername(username).addPassword(password).addPrivateKey(privateKey, passphrase);
            var authentication = builder.build();
            Log.debugf("Adding server: %s", server);
            selector.add(id, authentication);
        });

        return selector;
    }

    public static Optional<Path> resolveArtifact(String coords, List<RemoteRepository> remoteRepositories,
            RepositorySystemSession session, RepositorySystem system) {
        var request = new ArtifactRequest(new DefaultArtifact(coords), remoteRepositories, null);

        try {
            var result = system.resolveArtifact(session, request);
            var repository = result.getRepository();
            var artifact = result.getArtifact();
            var file = artifact.getFile();
            Log.debugf("Resolved artifact %s to file %s from repository %s", artifact, file, repository);
            return Optional.of(file.toPath());
        } catch (ArtifactResolutionException e) {
            var artifact = request.getArtifact();
            var repositories = request.getRepositories();
            var result = e.getResult();

            if (result.isMissing()) {
                return Optional.empty();
            } else {
                var message = String.format("Resolution of %s from %s failed", artifact, repositories);
                throw new RuntimeException(message, e);
            }
        }
    }

    public static String gavToCoords(Gav gav) {
        var groupId = gav.getGroupId();
        var artifactId = gav.getArtifactId();
        var extension = gav.getExtension() != null ? ":" + gav.getExtension() : "";
        var classifier = gav.getClassifier() != null ? ":" + gav.getClassifier() : "";
        var version = gav.getVersion();
        return groupId + ":" + artifactId + extension + classifier + ":" + version;
    }

    public static String pathToCoords(Path file) {
        var calculator = new M2GavCalculator();
        var str = normalize(file.toString(), true);
        var gav = calculator.pathToGav(str);

        if (gav == null) {
            throw new RuntimeException("Could not convert " + file + " to a GAV");
        }

        return gavToCoords(gav);
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
