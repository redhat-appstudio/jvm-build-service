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
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
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
import org.jboss.logging.Logger;

public class MavenUtils {
    private static final Logger Log = Logger.getLogger(MavenUtils.class);

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
}
