package com.redhat.hacbs.container.verifier;

import static com.redhat.hacbs.container.verifier.MavenUtils.newAuthenticationSelector;
import static com.redhat.hacbs.container.verifier.MavenUtils.newMirrorSelector;
import static com.redhat.hacbs.container.verifier.MavenUtils.newRepositorySystem;
import static com.redhat.hacbs.container.verifier.MavenUtils.newSettings;
import static com.redhat.hacbs.container.verifier.MavenUtils.pathToCoords;
import static com.redhat.hacbs.container.verifier.MavenUtils.resolveArtifact;
import static java.nio.file.FileVisitResult.CONTINUE;
import static org.apache.maven.repository.RepositorySystem.DEFAULT_REMOTE_REPO_ID;
import static org.apache.maven.repository.RepositorySystem.DEFAULT_REMOTE_REPO_URL;
import static org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;
import org.jboss.logging.Logger;

import com.redhat.hacbs.container.verifier.asm.JarInfo;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "verify-built-artifacts")
public class VerifyBuiltArtifactsCommand implements Callable<Integer> {
    private static final Logger Log = Logger.getLogger(VerifyBuiltArtifactsCommand.class);

    @Option(required = true, names = { "-r", "--repository-url" })
    String repositoryUrl;

    @Option(required = true, names = { "-d", "--deploy-path" })
    Path deployPath;

    @Option(names = { "-gs", "--global-settings" })
    Path globalSettingsFile;

    @Option(names = { "-s", "--settings" })
    Path settingsFile;

    @Option(names = { "--report-only" })
    boolean reportOnly;
    /**
     * The results file, will have 'true' written to it if verification passed, false otherwise.
     */
    @Option(names = { "--results-file" })
    Path resultsFile;
    private List<RemoteRepository> remoteRepositories;

    private RepositorySystem system;

    private boolean failed;

    private DefaultRepositorySystemSession session;

    public VerifyBuiltArtifactsCommand() {

    }

    @Override
    public Integer call() {
        try {
            if (session == null) {
                initMaven(globalSettingsFile, settingsFile);
            }

            Log.debugf("Deploy path: %s", deployPath);
            var passedCoords = (List<String>) new ArrayList<String>();
            var failedCoords = (List<String>) new ArrayList<String>();

            Files.walkFileTree(deployPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    var fileName = file.getFileName().toString();

                    if (fileName.endsWith(".jar")) {
                        try {
                            var relativeFile = deployPath.relativize(file);
                            var coords = pathToCoords(relativeFile);
                            Log.debugf("File %s has coordinates %s", relativeFile, coords);
                            var jarFailed = handleJar(file, coords);
                            failed |= jarFailed;

                            if (jarFailed) {
                                failedCoords.add(coords);
                            } else {
                                passedCoords.add(coords);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    return CONTINUE;
                }
            });

            if (!passedCoords.isEmpty()) {
                Log.infof("Passed: %s", passedCoords);
            }

            if (!failedCoords.isEmpty()) {
                Log.infof("Failed: %s", failedCoords);
            }

            if (resultsFile != null) {
                try {
                    Files.writeString(resultsFile, Boolean.toString(!failed));
                } catch (IOException ex) {
                    Log.errorf(ex, "Failed to write results");
                }
            }
            return (failed && !reportOnly ? 1 : 0);
        } catch (IOException e) {
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
        }
    }

    private void initMaven(Path globalSettingsFile, Path settingsFile) throws IOException {
        session = newSession();
        var settings = newSettings(globalSettingsFile, settingsFile);
        var mirrorSelector = newMirrorSelector(settings);
        session.setMirrorSelector(mirrorSelector);
        var authenticationSelector = newAuthenticationSelector(settings);
        session.setAuthenticationSelector(authenticationSelector);
        system = newRepositorySystem();
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
            Log.debugf("Using repository URL %s", repositoryUrl);
            remoteRepositories = List.of(new Builder("internal", "default", repositoryUrl).build());
        }
    }

    private boolean handleJar(Path file, String coords) {
        try {
            var optionalRemoteFile = resolveArtifact(coords, remoteRepositories, session, system);

            if (optionalRemoteFile.isEmpty()) {
                Log.warnf("Ignoring missing artifact %s", coords);
                return false;
            }

            var remoteFile = optionalRemoteFile.get();
            var left = new JarInfo(remoteFile);
            var right = new JarInfo(file);
            Log.infof("Verifying %s (%s, %s)", coords, remoteFile.toAbsolutePath(), file.toAbsolutePath());
            var failed = left.diffJar(right);
            Log.debugf("Verification of %s %s", coords, failed ? "failed" : "passed");
            return failed;
        } catch (OutOfMemoryError e) {
            //HUGE hack, but some things are just too large to diff in memory
            //but we would need a complete re-rewrite to handle this
            //these are usually tools that have heaps of classes shaded in
            //we just ignore this case for now
            Log.errorf(e, "Failed to analyse %s as it is too big", file);
            return false;
        }
    }
}
