package com.redhat.hacbs.container.deploy;

import static com.redhat.hacbs.container.verifier.MavenUtils.gavToCoords;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.apache.commons.io.FilenameUtils.removeExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import io.quarkus.logging.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "copy-artifacts")
public class CopyArtifactsCommand implements Runnable {
    @Option(required = true, names = {"-s", "--source-path"})
    Path sourcePath;

    @Option(required = true, names = {"-d", "--deploy-path"})
    Path deployPath;

    @Override
    public void run() {
        if (Files.isDirectory(deployPath)) {
            Log.warnf("Skipping copy artifacts since deploy path %s already exists", deployPath);
            return;
        }

        var pomFiles = new HashMap<Gav, Path>();
        var jarFiles = new ArrayList<Path>();

        try (var stream = Files.walk(sourcePath)) {
            stream.forEach(path -> {
                if (Files.isRegularFile(path)) {
                    var fileName = path.getFileName().toString();

                    if (fileName.endsWith(".pom") || fileName.equals("pom.xml")) {
                        try (var pomReader = Files.newBufferedReader(path)) {
                            var project = new MavenProject(new MavenXpp3Reader().read(pomReader));
                            var version = project.getVersion();

                            if (version.endsWith("@") || version.endsWith("-SNAPSHOT")) {
                                Log.warnf("Skipping POM %s with invalid version %s", fileName, version);
                            } else {
                                var packaging = project.getPackaging();
                                var gav = new Gav(project.getGroupId(), project.getArtifactId(), version, null, packaging, null, null, null, false, null, false, null);

                                if (!packaging.equals("pom") && !packaging.equals("jar")) {
                                    throw new RuntimeException("Unhandled packaging " + packaging + " for artifact " + gavToCoords(gav));
                                }

                                var existingPath = pomFiles.get(gav);

                                if (existingPath == null) {
                                    pomFiles.put(gav, path);
                                } else {
                                    var existingFileName = existingPath.getFileName().toString();

                                    // If multiple POM files are found, prefer the one named -<version>.pom
                                    if (existingFileName.equals("pom.xml") || !existingFileName.endsWith("-" + gav.getVersion() + ".pom")) {
                                        pomFiles.put(gav, path);
                                    }
                                }
                            }
                        } catch (IOException | XmlPullParserException e) {
                            throw new RuntimeException(e);
                        }
                    } else if (fileName.endsWith(".jar")) {
                        jarFiles.add(path);
                    }
                }
            });

            Log.infof("Found %d POMs and %d JARs in %s", pomFiles.size(), jarFiles.size(), sourcePath);

            for (var entry : pomFiles.entrySet()) {
                var gav = entry.getKey();
                Log.debugf("POM has GAV %s", gavToCoords(gav));
                var pathExt = new M2GavCalculator().gavToPath(gav).substring(1);
                var path = removeExtension(pathExt);
                var repoPath = Path.of(path);
                var fullPath = deployPath.resolve(repoPath);
                Log.debugf("Deploying to path %s", repoPath);
                var pomDestFile = Path.of(fullPath + ".pom");
                Files.createDirectories(fullPath.getParent());
                var pomSourceFile = entry.getValue();
                Log.infof("Copying %s to %s", pomSourceFile, pomDestFile);
                Files.copy(pomSourceFile, pomDestFile, REPLACE_EXISTING, COPY_ATTRIBUTES);
                var packaging = gav.getExtension();

                if (!packaging.equals("jar")) {
                    continue;
                }

                var jarDestFile = Path.of(fullPath + ".jar");
                var jarName = jarDestFile.getFileName().toString();
                var files = jarFiles.stream().filter(jarPath -> jarPath.getFileName().toString().equals(jarName)).toList();
                var jarSourceFile = (Path) null;

                if (!files.isEmpty()) {
                    jarSourceFile = files.get(0);
                } else {
                    var unversionedJarName = jarName.replace("-" + gav.getVersion(), "");
                    files = jarFiles.stream().filter(jarPath -> jarPath.getFileName().toString().equals(unversionedJarName)).toList();

                    if (!files.isEmpty()) {
                        jarSourceFile = files.get(0);
                    }
                }

                if (jarSourceFile != null) {
                    Log.infof("Copying %s to %s", jarSourceFile, jarDestFile);
                    Files.copy(jarSourceFile, jarDestFile, REPLACE_EXISTING, COPY_ATTRIBUTES);
                } else {
                    throw new RuntimeException("Could not find candidate for " + jarName + " in " + jarFiles);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
