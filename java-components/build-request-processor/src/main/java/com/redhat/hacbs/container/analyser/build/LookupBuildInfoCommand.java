package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.ANT;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.GRADLE;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.JDK;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.SBT;
import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.GOOGLE_JAVA_FORMAT_PLUGIN;
import static org.eclipse.jgit.api.ResetCommand.ResetType.HARD;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.apache.commons.cli.ParseException;
import org.apache.maven.cli.CLIManager;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.jgit.api.Git;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.container.analyser.build.ant.AntUtils;
import com.redhat.hacbs.container.analyser.build.gradle.GradleUtils;
import com.redhat.hacbs.container.analyser.build.maven.MavenDiscoveryTask;
import com.redhat.hacbs.container.analyser.location.VersionRange;
import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.scm.ScmInfo;
import com.redhat.hacbs.recipies.util.GitCredentials;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "lookup-build-info")
public class LookupBuildInfoCommand implements Runnable {

    @CommandLine.Option(names = "--cache-url", required = true)
    String cacheUrl;

    @CommandLine.Option(names = "--scm-url", required = true)
    String scmUrl;

    @CommandLine.Option(names = "--scm-tag", required = true)
    String tag;

    // This is a subdirectory to operate upon.
    @CommandLine.Option(names = "--context", required = true)
    String context;

    @CommandLine.Option(names = "--version", required = true)
    String version;

    @CommandLine.Option(names = "--message")
    Path message;

    @CommandLine.Option(names = "--private-repo")
    boolean privateRepo;
    /**
     * The build info, in JSON format as per BuildRecipe.
     * <p>
     * This just gives facts discovered by examining the checkout, it does not make any inferences from those facts (e.g. which
     * image to use).
     */
    @CommandLine.Option(names = "--build-info")
    Path buildInfo;

    @Inject
    Instance<MavenDiscoveryTask> mavenDiscoveryTasks;

    @Override
    public void run() {
        try {
            ScmInfo info = new ScmInfo("git", this.scmUrl);
            Log.infof("LookupBuildInfo::resolving " + info.getUri());

            CacheBuildInfoLocator buildInfoLocator = RestClientBuilder.newBuilder().baseUri(new URI(cacheUrl))
                    .build(CacheBuildInfoLocator.class);
            BuildRecipeInfo buildRecipeInfo = buildInfoLocator.resolveBuildInfo(info.getUri(), version);

            if (info.getBuildNameFragment() != null) {
                Log.infof("Using alternate name fragment of " + info.getBuildNameFragment());
                buildRecipeInfo = buildRecipeInfo.getAdditionalBuilds().get(info.getBuildNameFragment());
                if (buildRecipeInfo == null) {
                    throw new RuntimeException("Unknown build name " + info.getBuildNameFragment() + " for " + this.scmUrl
                            + " please add it to the additionalBuilds section");
                }
            }

            Log.infof("Checking out %s at tag %s", scmUrl, tag);
            doBuildAnalysis(info.getUriWithoutFragment(), tag, context, buildRecipeInfo, privateRepo, buildInfoLocator);

            if (message != null) {
                Files.createFile(message);
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build info for " + scmUrl);
            if (message != null) {
                try {
                    Files.writeString(message, "Failed to analyse build for " + scmUrl + ". Failure reason: " + e.getMessage());
                } catch (IOException ex) {
                    Log.errorf(e, "Failed to write result");
                }
            }
        }
    }

    private void doBuildAnalysis(String scmUrl, String scmTag, String context, BuildRecipeInfo buildRecipeInfo,
            boolean privateRepo, CacheBuildInfoLocator buildInfoLocator)
            throws Exception {
        boolean versionCorrect = false;
        var path = Files.createTempDirectory("checkout");
        try (var clone = Git.cloneRepository()
                .setCredentialsProvider(
                        new GitCredentials())
                .setURI(scmUrl)
                .setDirectory(path.toFile()).call()) {
            clone.reset().setMode(HARD).setRef(scmTag).call();
            boolean skipTests = !privateRepo;
            if (buildRecipeInfo != null && buildRecipeInfo.isRunTests()) {
                skipTests = false;
            }
            long time = clone.getRepository().parseCommit(clone.getRepository().resolve(scmTag)).getCommitTime() * 1000L;
            if (context != null) {
                path = path.resolve(context);
            }
            BuildInfo info = new BuildInfo();
            info.commitTime = time;
            Path pomFile = null;
            if (buildRecipeInfo != null && buildRecipeInfo.getAdditionalArgs() != null) {
                try {
                    CLIManager cliManager = new CLIManager();
                    org.apache.commons.cli.CommandLine commandLine = cliManager
                            .parse(buildRecipeInfo.getAdditionalArgs().toArray(new String[0]));
                    if (commandLine.hasOption(CLIManager.ALTERNATE_POM_FILE)) {
                        String alternatePomFile = commandLine.getOptionValue(CLIManager.ALTERNATE_POM_FILE);
                        if (alternatePomFile != null) {
                            pomFile = path.resolve(alternatePomFile);
                            if (Files.isDirectory(pomFile)) {
                                pomFile = pomFile.resolve("pom.xml");
                            }
                        }
                    }
                } catch (ParseException e) {
                    Log.warnf("Failed to parse maven command line %s", buildRecipeInfo.getAdditionalArgs());
                }
            }
            if (pomFile == null) {
                pomFile = path.resolve("pom.xml");
            }
            if (Files.isRegularFile(pomFile)) {
                Log.infof("Found Maven pom file at %s", pomFile);
                try (BufferedReader pomReader = Files.newBufferedReader(pomFile)) {
                    MavenXpp3Reader reader = new MavenXpp3Reader();
                    Model model = reader.read(pomReader);

                    //TODO: we should do discoery on the whole tree
                    List<DiscoveryResult> results = new ArrayList<>();
                    if (model.getVersion() != null && model.getVersion().endsWith("-SNAPSHOT")) {
                        //not tagged properly, deal with it automatically
                        info.enforceVersion = version;
                    } else if (model.getVersion() == null || Objects.equals(version, model.getVersion())) {
                        //if the version is null we can't run enforce version at this point
                        //version is correct, don't run enforce version as it can fail on things
                        //that are tagged correctly
                        versionCorrect = true;
                    }
                    results.add(new DiscoveryResult(
                            Map.of(JDK, new VersionRange("7", "17", "11"), MAVEN, new VersionRange("3.8", "3.8", "3.8")),
                            Integer.MIN_VALUE));
                    for (var i : mavenDiscoveryTasks) {
                        try {
                            var result = i.discover(model, path);
                            if (result != null) {
                                results.add(result);
                            }
                        } catch (Throwable t) {
                            Log.errorf(t, "Failed to run analysis step %s", i);
                        }
                    }

                    //look for repositories
                    for (var repo : handleRepositories(model, buildInfoLocator)) {
                        if (!info.repositories.contains(repo)) {
                            info.repositories.add(repo);
                        }
                    }

                    Collections.sort(results);
                    for (var i : results) {
                        info.tools.putAll(i.toolVersions);
                    }
                    var invocations = new ArrayList<>(
                            List.of(MAVEN, "install", "-Denforcer.skip", "-Dcheckstyle.skip",
                                    "-Drat.skip=true", "-Dmaven.deploy.skip=false", "-Dgpg.skip", "-Drevapi.skip",
                                    "-Djapicmp.skip", "-Dmaven.javadoc.failOnError=false", "-Dcobertura.skip=true"));
                    if (skipTests) {
                        //we assume private repos are essentially fresh tags we have control of
                        //so we should run the tests
                        //this can be controller via additional args if you still want to skip them
                        invocations.add("-DskipTests");
                    }
                    info.invocations.add(invocations);
                }
            }
            if (GradleUtils.isGradleBuild(path)) {
                Log.infof("Detected Gradle build in %s", path);
                var optionalGradleVersion = GradleUtils
                        .getGradleVersionFromWrapperProperties(GradleUtils.getPropertiesFile(path));
                var detectedGradleVersion = optionalGradleVersion.orElse("7");
                Log.infof("Detected Gradle version %s",
                        optionalGradleVersion.isPresent() ? detectedGradleVersion : "none");
                Log.infof("Chose Gradle version %s", detectedGradleVersion);
                String javaVersion;
                var specifiedJavaVersion = GradleUtils.getSpecifiedJavaVersion(path);

                if (!specifiedJavaVersion.isEmpty()) {
                    javaVersion = specifiedJavaVersion;
                    Log.infof("Chose Java version %s based on specified Java version", javaVersion);
                } else {
                    javaVersion = GradleUtils.getSupportedJavaVersion(detectedGradleVersion);
                    Log.infof("Chose Java version %s based on Gradle version detected", javaVersion);
                }

                if (GradleUtils.isInBuildGradle(path, GOOGLE_JAVA_FORMAT_PLUGIN)) {
                    javaVersion = "11";
                    Log.infof("Detected %s in build files and set Java version to %s", GOOGLE_JAVA_FORMAT_PLUGIN,
                            javaVersion);
                }

                info.tools.put(JDK, new VersionRange("8", "17", javaVersion));
                info.tools.put(GRADLE, new VersionRange(detectedGradleVersion, detectedGradleVersion, detectedGradleVersion));
                ArrayList<String> inv = new ArrayList<>();
                inv.add(GRADLE);
                inv.addAll(GradleUtils.getGradleArgs(path));
                if (skipTests) {
                    inv.add("-x");
                    inv.add("test");
                }
                info.invocations.add(inv);
                info.toolVersion = detectedGradleVersion;
            }
            if (Files.exists(path.resolve("build.sbt"))) {
                //TODO: initial SBT support, needs more work
                Log.infof("Detected SBT build in %s", path);
                info.tools.put(JDK, new VersionRange("7", "17", "8"));
                info.tools.put(SBT, new VersionRange("1.8.0", "1.8.0", "1.8.0"));
                info.toolVersion = "1.8.0";
                info.invocations.add(new ArrayList<>(
                        List.of(SBT, "--no-colors", "+publish"))); //the plus tells it to deploy for every scala version
            }
            if (AntUtils.isAntBuild(path)) {
                // XXX: It is possible to change the build file location via -buildfile/-file/-f or -find/-s
                Log.infof("Detected Ant build in %s", path);
                var specifiedJavaVersion = AntUtils.getJavaVersion(path);
                Log.infof("Detected Java version %s", !specifiedJavaVersion.isEmpty() ? specifiedJavaVersion : "none");
                var javaVersion = !specifiedJavaVersion.isEmpty() ? specifiedJavaVersion : "8";
                var antVersion = AntUtils.getAntVersionForJavaVersion(javaVersion);
                Log.infof("Chose Ant version %s", antVersion);
                //this should really be specific to the invocation
                info.tools.put(ANT, new VersionRange(antVersion, antVersion, antVersion));
                if (!info.tools.containsKey(JDK)) {
                    info.tools.put(JDK, AntUtils.getJavaVersionRange(path));
                }
                ArrayList<String> inv = new ArrayList<>();
                inv.add(ANT);
                inv.addAll(AntUtils.getAntArgs());
                info.invocations.add(inv);
                info.toolVersion = antVersion;
            }
            if (buildRecipeInfo != null) {
                if (buildRecipeInfo.getJavaVersion() != null) {
                    info.tools.put(JDK, new VersionRange(buildRecipeInfo.getJavaVersion(), buildRecipeInfo.getJavaVersion(),
                            buildRecipeInfo.getJavaVersion()));
                }
                if (buildRecipeInfo.getAlternativeArgs() != null && !buildRecipeInfo.getAlternativeArgs().isEmpty()) {
                    for (var i : info.invocations) {
                        var tool = i.get(0);
                        i.clear();
                        i.add(tool);
                        i.addAll(buildRecipeInfo.getAlternativeArgs());
                    }
                }
                if (buildRecipeInfo.getAdditionalArgs() != null) {
                    for (var i : info.invocations) {
                        i.addAll(buildRecipeInfo.getAdditionalArgs());
                    }
                }
                if (buildRecipeInfo.isEnforceVersion() && !versionCorrect) {
                    info.enforceVersion = version;
                }
                info.setRepositories(buildRecipeInfo.getRepositories());
                info.disableSubmodules = buildRecipeInfo.isDisableSubmodules();
                info.preBuildScript = buildRecipeInfo.getPreBuildScript();
                info.postBuildScript = buildRecipeInfo.getPostBuildScript();
                info.setAdditionalDownloads(buildRecipeInfo.getAdditionalDownloads());
                info.setAdditionalMemory(buildRecipeInfo.getAdditionalMemory());
                info.setAllowedDifferences(buildRecipeInfo.getAllowedDifferences());
                Log.infof("Got build recipe info %s", buildRecipeInfo);
            }
            ObjectMapper mapper = new ObjectMapper();
            Log.infof("Writing %s to %s", info, buildInfo.toFile());
            mapper.writeValue(buildInfo.toFile(), info);
        }
    }

    private Collection<String> handleRepositories(Model model, CacheBuildInfoLocator buildInfoLocator) {
        Set<String> repos = new HashSet<>();
        if (model.getRepositories() != null) {
            for (var i : model.getRepositories()) {
                repos.add(i.getUrl());
            }
        }
        if (model.getProfiles() != null) {
            for (var profile : model.getProfiles()) {
                if (profile.getRepositories() != null) {
                    for (var i : profile.getRepositories()) {
                        repos.add(i.getUrl());
                    }
                }
            }
        }
        if (repos.isEmpty()) {
            return List.of();
        }
        return buildInfoLocator.findRepositories(repos);
    }
}
