package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.ANT;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.GRADLE;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.SBT;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.eclipse.jgit.api.ResetCommand.ResetType.HARD;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.apache.commons.cli.ParseException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.cli.CLIManager;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.jgit.api.Git;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.image.json.ManifestTemplate;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.redhat.hacbs.container.analyser.build.ant.AntUtils;
import com.redhat.hacbs.container.analyser.build.gradle.GradleUtils;
import com.redhat.hacbs.container.analyser.build.maven.MavenJavaVersionDiscovery;
import com.redhat.hacbs.container.analyser.deploy.containerregistry.ContainerUtil;
import com.redhat.hacbs.container.results.ResultsUpdater;
import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.scm.ScmInfo;
import com.redhat.hacbs.recipies.util.GitCredentials;
import com.redhat.hacbs.resources.model.v1alpha1.Util;
import com.redhat.hacbs.resources.model.v1alpha1.jbsconfigstatus.ImageRegistry;

import io.quarkus.logging.Log;
import io.vertx.core.json.JsonObject;
import picocli.CommandLine;

@CommandLine.Command(name = "lookup-build-info")
public class LookupBuildInfoCommand implements Runnable {

    @CommandLine.Option(names = "--cache-url", required = true)
    String cacheUrl;

    @CommandLine.Option(names = "--scm-url", required = true)
    String scmUrl;

    @CommandLine.Option(names = "--scm-commit", required = true)
    String commit;

    @CommandLine.Option(names = "--scm-tag", required = true)
    String tag;

    // This is a subdirectory to operate upon (i.e. path)
    @CommandLine.Option(names = "--context")
    String context;

    @CommandLine.Option(names = "--version", required = true)
    String version;

    @CommandLine.Option(names = "--private-repo")
    boolean privateRepo;

    @CommandLine.Option(names = "--registries", description = "Denotes registries to search for preexisting builds")
    String registries;

    @ConfigProperty(name = "registry.token")
    Optional<String> envToken;

    @CommandLine.Option(names = "--task-run-name")
    String taskRun;

    @CommandLine.Option(names = "--tool-versions", description = "Available Tool Versions")
    String toolVersions;

    @Inject
    Instance<ResultsUpdater> resultsUpdater;

    @Override
    public void run() {
        try {
            ScmInfo info = new ScmInfo("git", this.scmUrl);
            Log.infof("LookupBuildInfo resolving %s for version %s ", info.getUri(), version);

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

            Log.infof("Cloning commit %s (tag %s)" + (context == null ? "" : " for path " + context), commit, tag);
            doBuildAnalysis(info.getUriWithoutFragment(), commit, context, buildRecipeInfo, privateRepo, buildInfoLocator);
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build info for " + scmUrl);
            resultsUpdater.get().updateResults(taskRun, Map.of(
                    "BUILD_INFO", "Failed to analyse build for " + scmUrl + ". Failure reason: " + e.getMessage()));
            System.exit(1);
        }
    }

    private Map<String, List<String>> parseToolVersions() {
        Map<String, List<String>> ret = new HashMap<>();
        for (var toolString : toolVersions.split(",")) {
            var parts = toolString.split(":");
            var tool = parts[0];
            var versions = parts[1].split(";");
            ret.put(tool, Arrays.asList(versions));
        }
        return ret;
    }

    private void doBuildAnalysis(String scmUrl, String scmTag, String context, BuildRecipeInfo buildRecipeInfo,
            boolean privateRepo, CacheBuildInfoLocator buildInfoLocator)
            throws Exception {
        Map<String, List<String>> availableTools = parseToolVersions();
        InvocationBuilder builder = new InvocationBuilder(buildRecipeInfo, availableTools, version);
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
            if (isNotBlank(context)) {
                path = path.resolve(context);
            }

            builder.setCommitTime(time);
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
                    //TODO: we should do discovery on the whole tree
                    if (model.getVersion() != null && model.getVersion().endsWith("-SNAPSHOT")) {
                        //not tagged properly, deal with it automatically
                        builder.enforceVersion(version);
                    } else if (model.getVersion() == null || Objects.equals(version, model.getVersion())) {
                        //if the version is null we can't run enforce version at this point
                        //version is correct, don't run enforce version as it can fail on things
                        //that are tagged correctly
                        builder.versionCorrect();
                        versionCorrect = true;
                    }
                    MavenJavaVersionDiscovery.filterJavaVersions(model, builder);

                    //look for repositories
                    for (var repo : handleRepositories(model, buildInfoLocator)) {
                        builder.addRepository(repo);
                    }

                    var invocations = new ArrayList<>(
                            List.of("install", "-Denforcer.skip", "-Dcheckstyle.skip",
                                    "-Drat.skip=true", "-Dmaven.deploy.skip=false", "-Dgpg.skip", "-Drevapi.skip",
                                    "-Djapicmp.skip", "-Dmaven.javadoc.failOnError=false", "-Dcobertura.skip=true",
                                    "-Dpgpverify.skip", "-Dspotbugs.skip", "-DallowIncompleteProjects"));
                    if (skipTests) {
                        //we assume private repos are essentially fresh tags we have control of
                        //so we should run the tests
                        //this can be controller via additional args if you still want to skip them
                        invocations.add("-DskipTests");
                    }
                    if (model.getProfiles() != null) {
                        for (var profile : model.getProfiles()) {
                            if (Objects.equals(profile.getId(), "release")) {
                                invocations.add("-Prelease");
                            }
                        }
                    }
                    builder.addToolInvocation(MAVEN, invocations);
                }
            }
            if (GradleUtils.isGradleBuild(path)) {
                Log.infof("Detected Gradle build in %s", path);
                var optionalGradleVersion = GradleUtils
                        .getGradleVersionFromWrapperProperties(GradleUtils.getPropertiesFile(path));
                if (optionalGradleVersion.isPresent()) {
                    builder.discoveredToolVersion(GRADLE, optionalGradleVersion.get());
                }
                var detectedGradleVersion = optionalGradleVersion.orElse("7");
                Log.infof("Detected Gradle version %s",
                        optionalGradleVersion.isPresent() ? detectedGradleVersion : "none");
                Log.infof("Chose Gradle version %s", detectedGradleVersion);
                String javaVersion;
                var specifiedJavaVersion = GradleUtils.getSpecifiedJavaVersion(path);

                if (!specifiedJavaVersion.isEmpty()) {
                    builder.minJavaVersion(new JavaVersion(specifiedJavaVersion));
                    javaVersion = specifiedJavaVersion;
                    Log.infof("Chose min Java version %s based on specified Java version", javaVersion);
                }
                ArrayList<String> inv = new ArrayList<>();
                inv.addAll(GradleUtils.getGradleArgs(path));
                if (skipTests) {
                    inv.add("-x");
                    inv.add("test");
                }
                builder.addToolInvocation(GRADLE, inv);
            }
            if (Files.exists(path.resolve("build.sbt"))) {
                //TODO: initial SBT support, needs more work
                Log.infof("Detected SBT build in %s", path);
                builder.addToolInvocation(SBT, List.of("--no-colors", "+publish"));
            }
            if (AntUtils.isAntBuild(path)) {
                //TODO: this needs work, too much hard coded stuff, just try all and builds
                // XXX: It is possible to change the build file location via -buildfile/-file/-f or -find/-s
                Log.infof("Detected Ant build in %s", path);
                //                var specifiedJavaVersion = AntUtils.getJavaVersion(path);
                //                Log.infof("Detected Java version %s", !specifiedJavaVersion.isEmpty() ? specifiedJavaVersion : "none");
                //                var javaVersion = !specifiedJavaVersion.isEmpty() ? specifiedJavaVersion : "8";
                //                var antVersion = AntUtils.getAntVersionForJavaVersion(javaVersion);
                //                Log.infof("Chose Ant version %s", antVersion);
                //                //this should really be specific to the invocation
                //                info.tools.put(ANT, new VersionRange(antVersion, antVersion, antVersion));
                //                if (!info.tools.containsKey(JDK)) {
                //                    info.tools.put(JDK, AntUtils.getJavaVersionRange(path));
                //                }
                ArrayList<String> inv = new ArrayList<>();
                inv.addAll(AntUtils.getAntArgs());
                builder.addToolInvocation(ANT, inv);
            }
            if (versionCorrect) {
                builder.versionCorrect();
            }
            if (buildRecipeInfo != null) {
                Log.infof("Got build recipe info %s", buildRecipeInfo);
            }
            if (registries != null) {
                String[] splitRegistries = registries.split(";", -1);

                for (String value : splitRegistries) {
                    ImageRegistry registry = Util.parseRegistry(value);
                    // Meant to match Go code that does
                    // util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
                    String contextPath = context == null ? "" : context;
                    String prependTag = isBlank(registry.getPrependTag()) ? "" : registry.getPrependTag() + "_";
                    String imageId = prependTag + DigestUtils.md5Hex(scmUrl + tag + contextPath);
                    String port = isBlank(registry.getPort()) ? "443" : registry.getPort();
                    String fullName = registry.getHost() + (port.equals("443") ? "" : ":" + port) + "/" + registry.getOwner()
                            + "/" + registry.getRepository() + ":" + imageId;
                    Credential credential = ContainerUtil.processToken(fullName, envToken.orElse(""));

                    Log.infof("Examining registry %s for image %s", fullName, imageId);
                    try {
                        //TODO consider authentication whether via env token or dockerconfig. Would need to pass
                        // token in env as per ContainerRegistryDeployer?
                        ImageReference reference = ImageReference.parse(fullName);
                        RegistryClient registryClient = ContainerUtil.getRegistryClient(reference, credential,
                                registry.getInsecure());
                        Optional<ManifestAndDigest<ManifestTemplate>> manifestAndDigest = registryClient
                                .checkManifest(reference.getTag().get());

                        if (manifestAndDigest.isPresent()) {
                            // Found a potential manifest match for the build - now obtain the container manifest JSON
                            // so we can obtain the correct GAVs.
                            Blob blob = registryClient.pullBlob(((OciManifestTemplate) manifestAndDigest.get()
                                    .getManifest()).getContainerConfiguration()
                                    .getDigest(),
                                    s -> {
                                    }, s -> {
                                    });

                            ByteArrayOutputStream bao = new ByteArrayOutputStream();
                            blob.writeTo(bao);
                            String fromManifest = bao.toString(Charset.defaultCharset());
                            JsonObject tagListJson = new JsonObject(fromManifest);
                            JsonObject configJson = tagListJson.getJsonObject("config");
                            JsonObject labels = configJson.getJsonObject("Labels");
                            // ContainerRegistryDeployer uses a comma separated list
                            List<String> gavList;
                            if (labels.getMap().containsKey("io.jvmbuildservice.gavs")) {
                                gavList = Arrays.asList(
                                        labels.getString("io.jvmbuildservice.gavs").split(",", -1));
                            } else {
                                // Backwards compatibility check for builds with older label format.
                                String g = labels.getString("groupId");
                                String v = labels.getString("version");
                                gavList = new ArrayList<>();
                                Arrays.stream(labels.getString("artifactId").split(","))
                                        .forEach(a -> gavList.add(g + ":" + a + ":" + v));
                            }
                            builder.existingBuild(gavList, fullName, manifestAndDigest.get().getDigest().toString());
                            Log.infof(
                                    "Found GAVs " + gavList + " and digest " + manifestAndDigest.get()
                                            .getDigest());
                            break;
                        }
                    } catch (InvalidImageReferenceException | IOException | RegistryException e) {
                        // Manually deleting a tag on quay.io gives
                        // Tried to pull image manifest for <...>:975ea3800099190263d38f051c1a188a but failed
                        //    because: unknown error code: TAG_EXPIRED
                        if (!e.getMessage().contains("TAG_EXPIRED")) {
                            throw new RuntimeException(e);
                        }
                        Log.errorf("Registry tag expired - " + e.getMessage());
                    }
                }
            }
            if (taskRun != null) {
                var info = builder.build(buildInfoLocator);
                Log.infof("Writing %s", info);
                resultsUpdater.get().updateResults(taskRun, Map.of("BUILD_INFO",
                        ResultsUpdater.MAPPER.writeValueAsString(info)));
            }
        }
    }

    private Collection<String> handleRepositories(Model model, CacheBuildInfoLocator buildInfoLocator) {
        Set<String> repos = new HashSet<>();
        if (model.getRepositories() != null) {
            for (var i : model.getRepositories()) {
                repos.add(i.getUrl());
            }
        }
        if (model.getPluginRepositories() != null) {
            for (var i : model.getPluginRepositories()) {
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
                if (profile.getPluginRepositories() != null) {
                    for (var i : profile.getPluginRepositories()) {
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
