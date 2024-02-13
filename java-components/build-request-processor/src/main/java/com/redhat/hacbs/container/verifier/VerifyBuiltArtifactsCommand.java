package com.redhat.hacbs.container.verifier;

import static com.redhat.hacbs.container.verifier.MavenUtils.newAuthenticationSelector;
import static com.redhat.hacbs.container.verifier.MavenUtils.newMirrorSelector;
import static com.redhat.hacbs.container.verifier.MavenUtils.newSettings;
import static com.redhat.hacbs.container.verifier.MavenUtils.pathToCoords;
import static com.redhat.hacbs.container.verifier.MavenUtils.resolveArtifact;
import static java.nio.file.FileVisitResult.CONTINUE;
import static org.apache.commons.lang3.StringUtils.endsWithAny;
import static org.apache.maven.repository.RepositorySystem.DEFAULT_REMOTE_REPO_ID;
import static org.apache.maven.repository.RepositorySystem.DEFAULT_REMOTE_REPO_URL;
import static org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession;
import static picocli.CommandLine.ArgGroup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;

import com.redhat.hacbs.container.results.ResultsUpdater;
import com.redhat.hacbs.container.verifier.asm.ClassVersion;
import com.redhat.hacbs.container.verifier.asm.JarInfo;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.logging.Log;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "verify-built-artifacts")
public class VerifyBuiltArtifactsCommand implements Callable<Integer> {
    private static final String CLASS_VERSION_CHANGED_REGEX = "^\\^:(?<fileName>[^:]+):(?<className>[^:]+):version:(?<fromVersion>[0-9.]+)>(?<toVersion>[0-9.]+)$";

    private static final String CLASS_REMOVED_REGEX = "^-:(?<fileName>[^:]+):class:(?<className>[^:]+)$";

    static final Pattern CLASS_VERSION_CHANGED_PATTERN = Pattern.compile(CLASS_VERSION_CHANGED_REGEX);

    static final Pattern CLASS_REMOVED_PATTERN = Pattern.compile(CLASS_REMOVED_REGEX);

    static class LocalOptions {
        @Option(required = true, names = { "-of", "--original-file" })
        Path originalFile;

        @Option(required = true, names = { "-nf", "--new-file" })
        Path newFile;
    }

    static class MavenOptions {
        @Option(required = true, names = { "-r", "--repository-url" })
        String repositoryUrl;

        @Option(required = true, names = { "-d", "--deploy-path" })
        Path deployPath;

        @Option(names = { "-gs", "--global-settings" })
        Path globalSettingsFile;

        @Option(names = { "-s", "--settings" })
        Path settingsFile;
    }

    static class Options {
        @ArgGroup(exclusive = false)
        LocalOptions localOptions = new LocalOptions();

        @ArgGroup(exclusive = false)
        MavenOptions mavenOptions = new MavenOptions();

    }

    @ArgGroup(multiplicity = "1")
    Options options = new Options();

    @CommandLine.Option(names = "--task-run-name")
    String taskRunName;

    @Option(names = { "--report-only" })
    boolean reportOnly;
    /**
     * The results file, will have 'true' written to it if verification passed, false otherwise.
     */
    @Option(names = { "--results-file" })
    Path resultsFile;

    @Option(names = { "-x", "--excludes" })
    Set<String> excludes = new LinkedHashSet<>();

    @Option(names = { "-e", "--excludes-file" })
    Path excludesFile;

    @Option(names = { "--threads" }, defaultValue = "5")
    int threads;
    private List<RemoteRepository> remoteRepositories;

    private RepositorySystem system;

    private DefaultRepositorySystemSession session;

    @Inject
    Instance<ResultsUpdater> resultsUpdater;

    @Inject
    BootstrapMavenContext mvnCtx;

    public VerifyBuiltArtifactsCommand() {

    }

    @Override
    public Integer call() {
        if (threads < 1) {
            threads = 1;
        }
        ExecutorService executorService = Executors.newFixedThreadPool(threads);
        try {
            var excludes = getExcludes();

            if (options.localOptions.originalFile != null && options.localOptions.newFile != null) {
                var numErrors = handleJar(options.localOptions.originalFile, options.localOptions.newFile, excludes);
                return (!numErrors.isEmpty() && !reportOnly ? 1 : 0);
            }

            if (session == null) {
                initMaven(options.mavenOptions.globalSettingsFile, options.mavenOptions.settingsFile);
                session.setReadOnly();
            }

            var futureResults = new HashMap<String, Future<List<String>>>();

            Files.walkFileTree(options.mavenOptions.deployPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    var fileName = file.getFileName().toString();

                    if (fileName.endsWith(".jar")) {
                        try {
                            var relativeFile = options.mavenOptions.deployPath.relativize(file);
                            var coords = pathToCoords(relativeFile);
                            Log.debugf("File %s has coordinates %s", relativeFile, coords);
                            if (endsWithAny(fileName, "-javadoc.jar", "-tests.jar", "-sources.jar")) {
                                Log.debugf("Skipping file %s", relativeFile);
                            } else {
                                var failures = executorService.submit(() -> handleJar(file, coords, excludes));
                                futureResults.put(coords, failures);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    return CONTINUE;
                }
            });

            boolean failed = false;
            var verificationResults = new HashMap<String, List<String>>();
            for (var e : futureResults.entrySet()) {
                List<String> results = e.getValue().get();
                verificationResults.put(e.getKey(), results);
                if (results.isEmpty()) {
                    Log.infof("Passed: %s", e.getKey());
                } else {
                    Log.errorf("Failed: %s:\n%s", e.getKey(), String.join("\n", results));
                    failed = true;

                    for (var error : results) {
                        var matcher = CLASS_VERSION_CHANGED_PATTERN.matcher(error);
                        if (matcher.matches()) {
                            var fileName = matcher.group("fileName");
                            var className = matcher.group("className");
                            var fromVersion = ClassVersion.fromVersion(matcher.group("fromVersion"));
                            var toVersion = ClassVersion.fromVersion(matcher.group("toVersion"));
                            var sourceVersion = ClassVersion.toJavaVersion(fromVersion);
                            Log.errorf("Class %s version in file %s changed from %s to %s. Rerun build with -source %s -target %s",
                                className, fileName, fromVersion,
                                toVersion, sourceVersion, sourceVersion);
                            reportOnly = false;
                            break;
                        }
                        matcher = CLASS_REMOVED_PATTERN.matcher(error);
                        if (matcher.matches()) {
                            var fileName = matcher.group("fileName");
                            var className = matcher.group("className");
                            Log.errorf("Class %s in file %s was removed", className, fileName);
                           reportOnly = false;
                           break;
                        }
                    }
                }
            }

            if (resultsFile != null) {
                try {
                    Files.writeString(resultsFile, Boolean.toString(!failed));
                } catch (IOException ex) {
                    Log.errorf(ex, "Failed to write results");
                }
            }
            if (taskRunName != null) {
                var json = ResultsUpdater.MAPPER.writeValueAsString(verificationResults);
                Log.infof("Writing verification results %s", json);
                resultsUpdater.get().updateResults(taskRunName, Map.of("VERIFICATION_RESULTS", json));
            }

            return (failed && !reportOnly ? 1 : 0);
        } catch (Exception e) {
            Log.errorf("%s", e.getMessage(), e);
            if (resultsFile != null) {
                try {
                    Files.writeString(resultsFile, "false");
                } catch (IOException ex) {
                    Log.errorf(ex, "Failed to write results");
                }
            }
            if (reportOnly) {
                return 0;
            }
            return 1;
        } finally {
            executorService.shutdown();
        }
    }

    private List<String> getExcludes() throws IOException {
        var newExcludes = new ArrayList<String>();
        if (excludesFile != null) {
            if (!Files.isRegularFile(excludesFile) || !Files.isReadable(excludesFile)) {
                throw new RuntimeException("Error reading excludes file " + excludesFile.toAbsolutePath());
            }

            var lines = Files.readAllLines(excludesFile);
            newExcludes.addAll(lines);
        }

        for (var exclude : excludes) {
            if (!exclude.matches("^[+-^]:.*$")) {
                Log.errorf("Invalid exclude %s", exclude);
                return null;
            }

            newExcludes.add(exclude.replaceAll("^([+-^])", "^\\\\$1"));
        }

        return newExcludes;
    }

    private void initMaven(Path globalSettingsFile, Path settingsFile)
            throws IOException, BootstrapMavenException {
        session = newSession();
        var settings = newSettings(globalSettingsFile, settingsFile);
        var mirrorSelector = newMirrorSelector(settings);
        session.setMirrorSelector(mirrorSelector);
        var authenticationSelector = newAuthenticationSelector(settings);
        session.setAuthenticationSelector(authenticationSelector);
        system = mvnCtx.getRepositorySystem();
        var localRepositoryDirectory = Files.createTempDirectory("verify-built-artifacts-");
        var localRepository = new LocalRepository(localRepositoryDirectory.toFile());
        var manager = system.newLocalRepositoryManager(session, localRepository);
        session.setLocalRepositoryManager(manager);
        var centralRepository = new Builder(DEFAULT_REMOTE_REPO_ID, "default", DEFAULT_REMOTE_REPO_URL).build();
        var mirror = mirrorSelector.getMirror(centralRepository);

        if (mirror != null) {
            Log.debugf("Mirror for %s: %s", centralRepository.getId(), mirror);
            remoteRepositories = List.of(mirror);
        } else {
            Log.debugf("Using repository URL %s", options.mavenOptions.repositoryUrl);
            remoteRepositories = List.of(new Builder("internal", "default", options.mavenOptions.repositoryUrl).build());
        }
    }

    private List<String> handleJar(Path remoteFile, Path file, List<String> excludes) {
        var left = new JarInfo(remoteFile);
        var right = new JarInfo(file);
        return left.diffJar(right, excludes);
    }

    private List<String> handleJar(Path file, String coords, List<String> excludes) {
        try {
            var optionalRemoteFile = resolveArtifact(coords, remoteRepositories, session, system);

            if (optionalRemoteFile.isEmpty()) {
                Log.warnf("Ignoring missing artifact %s", coords);
                return List.of();
            }

            var remoteFile = optionalRemoteFile.get();
            Log.infof("Verifying %s (%s, %s)", coords, remoteFile.toAbsolutePath(), file.toAbsolutePath());
            var errors = handleJar(remoteFile, file, excludes);
            int numFailures = errors.size();

            Log.debugf("Verification of %s %s", coords, numFailures > 0 ? "failed" : "passed");

            return errors;
        } catch (OutOfMemoryError e) {
            //HUGE hack, but some things are just too large to diff in memory
            //but we would need a complete re-rewrite to handle this
            //these are usually tools that have heaps of classes shaded in
            //we just ignore this case for now
            Log.errorf(e, "Failed to analyse %s as it is too big", file);
            return List.of();
        }
    }
}
