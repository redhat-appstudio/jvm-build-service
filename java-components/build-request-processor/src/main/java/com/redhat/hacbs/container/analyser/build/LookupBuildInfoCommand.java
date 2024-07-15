package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.ANT;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.GRADLE;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.SBT;
import static com.redhat.hacbs.container.verifier.MavenUtils.getBuildJdk;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.eclipse.jgit.api.ResetCommand.ResetType.HARD;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.apache.commons.cli.ParseException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.maven.cli.CLIManager;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logmanager.LogManager;

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
import com.redhat.hacbs.container.deploy.containerregistry.ContainerUtil;
import com.redhat.hacbs.container.results.ResultsUpdater;
import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.build.BuildRecipeInfoManager;
import com.redhat.hacbs.recipes.scm.ScmInfo;
import com.redhat.hacbs.recipes.util.GitCredentials;
import com.redhat.hacbs.resources.model.v1alpha1.Util;
import com.redhat.hacbs.resources.model.v1alpha1.jbsconfigstatus.ImageRegistry;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.options.BootstrapMavenOptions;
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

    @CommandLine.Option(names = "--artifact", description = "Artifact to get manifest from")
    String artifact;

    @CommandLine.Option(names = "--build-recipe-path")
    String buildRecipePath;

    @Inject
    Instance<ResultsUpdater> resultsUpdater;

    @Inject
    BootstrapMavenContext mavenContext;

    // Variable so can be overridden by the tests.
    String cachePath = "/v2/cache/rebuild-default/0";

    @Override
    public void run() {
        try {
            ScmInfo scmInfo = new ScmInfo("git", this.scmUrl);
            Log.infof("LookupBuildInfo resolving %s for version %s ", scmInfo.getUri(), version);

            CacheBuildInfoLocator buildInfoLocator = RestClientBuilder.newBuilder().baseUri(new URI(cacheUrl))
                    .build(CacheBuildInfoLocator.class);
            BuildRecipeInfo buildRecipeInfo;

            if (buildRecipePath != null) {
                Log.infof("Using build recipe from %s", buildRecipePath);
                BuildRecipeInfoManager buildRecipeInfoManager = new BuildRecipeInfoManager();
                buildRecipeInfo = buildRecipeInfoManager.parse(Path.of(buildRecipePath));
            } else {
                buildRecipeInfo = buildInfoLocator.resolveBuildInfo(scmInfo.getUri(), version);

                if (scmInfo.getBuildNameFragment() != null) {
                    Log.infof("Using alternate name fragment of " + scmInfo.getBuildNameFragment());
                    buildRecipeInfo = buildRecipeInfo.getAdditionalBuilds().get(scmInfo.getBuildNameFragment());
                    if (buildRecipeInfo == null) {
                        throw new RuntimeException("Unknown build name " + scmInfo.getBuildNameFragment() + " for " + this.scmUrl
                            + " please add it to the additionalBuilds section");
                    }
                }
            }

            Log.infof("Cloning commit %s (tag %s)" + (context == null ? "" : " for path " + context), commit, tag);
            var info = doBuildAnalysis(scmInfo.getUriWithoutFragment(), buildRecipeInfo, buildInfoLocator);
            var infoString = ResultsUpdater.MAPPER.writeValueAsString(info);
            Log.infof("Writing %s", infoString);
            if (taskRun != null) {
                resultsUpdater.get().updateResults(taskRun, Map.of("BUILD_INFO",
                        infoString));
            }

        } catch (Exception e) {
            Log.errorf(e, "Failed to process build info for " + scmUrl);
            resultsUpdater.get().updateResults(taskRun, Map.of(
                    "BUILD_INFO", "Failed to analyse build for " + scmUrl + ". Failure reason: " + e.getMessage()));
            System.exit(1);
        }
    }

    BuildInfo doBuildAnalysis(String scmUrl, BuildRecipeInfo buildRecipeInfo, CacheBuildInfoLocator buildInfoLocator)
            throws Exception {
        Map<String, List<String>> availableTools = parseToolVersions();
        InvocationBuilder builder = new InvocationBuilder(buildRecipeInfo, availableTools, version);

        var path = Files.createTempDirectory("checkout");
        StringWriter writer = new StringWriter();
        TextProgressMonitor monitor = new TextProgressMonitor(writer) {
            // Don't want percent updates, just final summaries.
            protected void onUpdate(String taskName, int workCurr, Duration duration)
            {
            }

            protected void onUpdate(String taskName, int cmp, int totalWork, int pcnt, Duration duration)
            {
            }
        };
        monitor.showDuration(true);
        var repoClone = Git.cloneRepository()
            .setCredentialsProvider(new GitCredentials())
            .setURI(scmUrl)
            .setProgressMonitor(monitor)
            .setNoCheckout(true)
            .setDirectory(path.toFile());
        if (!commit.equals(tag)) {
            // If commit and tag are identical it likely means there is no tag located and we are cloning
            // a hash mid-tree. Therefore, don't set the depth.
            repoClone.setDepth(1);
        }
        try (var clone = repoClone.call()) {
            Log.infof("Clone summary:\n%s", writer.toString().replaceAll( "(?m)^\\s+", ""));
            clone.reset().setMode(HARD).setRef(commit).call();
            boolean skipTests = !privateRepo;
            if (buildRecipeInfo != null && buildRecipeInfo.isRunTests()) {
                skipTests = false;
            }
            builder.setCommitTime(
                    clone.getRepository().parseCommit(clone.getRepository().resolve(commit)).getCommitTime() * 1000L);
            // The context path is passed in from the golang dependencybuild::createLookupBuildInfoPipeline
            // Currently the context path is used as a base, and if that fails the auto-discovery then starts.
            if (isNotBlank(context)) {
                path = path.resolve(context);
            }

            // Auto-magic discovery - Try looking in this directory and if that fails try immediate subdirectories for
            // a potential build. If multiple subdirectories contain build files an error currently an error is thrown.
            var buildScriptPaths = searchForBuildScripts(buildRecipeInfo, path);
            if (buildScriptPaths.isEmpty()) {
                Log.warnf("Unable to locate a build script within %s. Searching subdirs...", path);
                List<Path> paths = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (Path potentialSubDir : stream) {
                        if (Files.isDirectory(potentialSubDir) && !Files.isHidden(potentialSubDir)) {
                            Log.infof("Looking for build scripts in %s...", potentialSubDir);
                            var s = searchForBuildScripts(buildRecipeInfo, potentialSubDir);
                            if (!s.isEmpty()) {
                                Log.infof("Found build script(s) in %s: %s", potentialSubDir, s);
                                paths.add(potentialSubDir);
                                buildScriptPaths = s;
                            }
                        }
                    }
                }
                // If we have found multiple build files that is a problem (as we don't know which to choose). Otherwise
                // pass back the new context directory.
                if (paths.size() > 1) {
                    Log.errorf("Multiple subdirectories have build files: %s", paths);
                    throw new RuntimeException("Multiple subdirectories have build files: " + paths);
                } else if (paths.size() == 1) {
                    path = paths.get(0);
                    String foundContext = path.getFileName().toString();
                    Log.warnf("Setting context path %s", foundContext);
                    builder.setContextPath(foundContext);
                } else {
                    Log.errorf("No directories have build files");
                    throw new RuntimeException("No directories have build files");
                }
            }

            Log.infof("Build script(s) in %s: %s", path, buildScriptPaths);

            var buildScript = (Path) null;

            if (buildRecipeInfo == null || buildRecipeInfo.getTool() == null
                    || Objects.equals(buildRecipeInfo.getTool(), MAVEN)) {
                if ((buildScript = buildScriptPaths.get(MAVEN)) != null) {
                    handleMavenBuild(builder, buildScript, skipTests, buildInfoLocator);
                }
            }
            if (buildRecipeInfo == null || buildRecipeInfo.getTool() == null
                    || Objects.equals(buildRecipeInfo.getTool(), GRADLE)) {
                if ((buildScript = buildScriptPaths.get(GRADLE)) != null) {
                    handleGradleBuild(builder, buildScript, skipTests);
                }
            }
            if (buildRecipeInfo == null || buildRecipeInfo.getTool() == null
                    || Objects.equals(buildRecipeInfo.getTool(), SBT)) {
                if ((buildScript = buildScriptPaths.get(SBT)) != null) {
                    handleSbtBuild(builder, buildScript);
                }
            }
            if (buildRecipeInfo == null || buildRecipeInfo.getTool() == null
                    || Objects.equals(buildRecipeInfo.getTool(), ANT)) {
                if ((buildScript = buildScriptPaths.get(ANT)) != null) {
                    handleAntBuild(builder, buildScript);
                }
            }

            if (artifact != null && (buildRecipeInfo == null || buildRecipeInfo.getJavaVersion() == null)) {
                Log.infof("Lookup Build JDK for artifact %s", artifact);
                var optBuildJdk = getBuildJdk(cacheUrl + cachePath, artifact);
                if (optBuildJdk.isPresent()) {
                    var buildJdk = optBuildJdk.get();
                    Log.infof("Setting build JDK to %s for artifact %s", buildJdk.version(), artifact);
                    builder.setPreferredJavaVersion(buildJdk);
                } else {
                    Log.infof("No Build JDK found in JAR manifest");
                }
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

            return builder.build(buildInfoLocator);
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

    Collection<String> handleRepositories(Path pomFile, CacheBuildInfoLocator buildInfoLocator,
            boolean releaseProfile) {
        try {
            if (releaseProfile) {
                System.setProperty(BootstrapMavenOptions.QUARKUS_INTERNAL_MAVEN_CMD_LINE_ARGS, "-Prelease");
            }
            var config = BootstrapMavenContext.config();
            config.setEffectiveModelBuilder(true);
            config.setPreferPomsFromWorkspace(true);
            config.setWarnOnFailedWorkspaceModules(true);
            config.setRepositorySystem(mavenContext.getRepositorySystem());
            config.setRemoteRepositoryManager(mavenContext.getRemoteRepositoryManager());

            Set<String> result = new HashSet<>(internalHandleRepositories(config, pomFile));
            if (result.isEmpty()) {
                return List.of();
            }
            Log.infof("Found repositories %s", result);
            return buildInfoLocator.findRepositories(result);
        } catch (Exception e) {
            Log.error("Failed to resolve repositories", e);
            return List.of();
        } finally {
            System.clearProperty(BootstrapMavenOptions.QUARKUS_INTERNAL_MAVEN_CMD_LINE_ARGS);
        }
    }

    private Collection<String> internalHandleRepositories(
            io.quarkus.bootstrap.resolver.maven.BootstrapMavenContextConfig<?> config,
            Path pomFile)
            throws BootstrapMavenException {
        Set<String> result = new HashSet<>();
        Path parent = pomFile.getParent();
        config.setCurrentProject(parent.toString());
        config.setRootProjectDir(parent);
        Handler current = LogManager.getLogManager().getLogger("").getHandlers()[0];
        Level original = current.getLevel();

        BootstrapMavenContext newCtx;
        // The Quarkus Bootstrap can log out vast amounts of information with setWarnOnFailedWorkspaceModules
        // enabled at warn level (rather than debug) so quieten it down.
        try {
            current.setLevel(Level.SEVERE);
            newCtx = new BootstrapMavenContext(config);
        } finally {
            current.setLevel(original);
        }
        List<RemoteRepository> repositories = newCtx.getRemoteRepositories();
        for (RemoteRepository repository : repositories) {
            result.add(repository.getUrl());
        }
        List<RemoteRepository> pluginRepositories = newCtx.getRemotePluginRepositories();
        for (RemoteRepository repository : pluginRepositories) {
            result.add(repository.getUrl());
        }
        for (var e : newCtx.getWorkspace().getProjects().entrySet()) {
            Model model = e.getValue().getModelBuildingResult().getEffectiveModel();
            if (model.getRepositories() != null) {
                for (var i : model.getRepositories()) {
                    result.add(i.getUrl());
                }
            }
            if (model.getPluginRepositories() != null) {
                for (var i : model.getPluginRepositories()) {
                    result.add(i.getUrl());
                }
            }
        }

        return result;
    }

    private Map<String, Path> searchForBuildScripts(BuildRecipeInfo buildRecipeInfo, Path path) {
        var paths = new HashMap<String, Path>();
        var pomFile = (Path) null;

        if (buildRecipeInfo != null && buildRecipeInfo.getAdditionalArgs() != null) {
            try {
                var cliManager = new CLIManager();
                var commandLine = cliManager.parse(buildRecipeInfo.getAdditionalArgs().toArray(new String[0]));

                if (commandLine.hasOption(CLIManager.ALTERNATE_POM_FILE)) {
                    var alternatePomFile = commandLine.getOptionValue(CLIManager.ALTERNATE_POM_FILE);

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
            paths.put(MAVEN, pomFile);
        }

        GradleUtils.getGradleBuild(path).ifPresent(p -> paths.put(GRADLE, p));

        var buildSbt = path.resolve("build.sbt");

        if (Files.isRegularFile(buildSbt)) {
            paths.put(SBT, buildSbt);
        }

        AntUtils.getAntBuild(path).ifPresent(p -> paths.put(ANT, p));

        return paths;
    }

    private void handleAntBuild(InvocationBuilder builder, Path antFile) {
        //TODO: this needs work, too much hard coded stuff, just try all and builds
        // XXX: It is possible to change the build file location via -buildfile/-file/-f or -find/-s
        Log.infof("Detected Ant build file %s", antFile);
        //                var specifiedJavaVersion = AntUtils.getJavaVersion(antFile);
        //                Log.infof("Detected Java version %s", !specifiedJavaVersion.isEmpty() ? specifiedJavaVersion : "none");
        //                var javaVersion = !specifiedJavaVersion.isEmpty() ? specifiedJavaVersion : "8";
        //                var antVersion = AntUtils.getAntVersionForJavaVersion(javaVersion);
        //                Log.infof("Chose Ant version %s", antVersion);
        //                //this should really be specific to the invocation
        //                info.tools.put(ANT, new VersionRange(antVersion, antVersion, antVersion));
        //                if (!info.tools.containsKey(JDK)) {
        //                    info.tools.put(JDK, AntUtils.getJavaVersionRange(path));
        //                }
        ArrayList<String> inv = new ArrayList<>(AntUtils.getAntArgs());
        builder.addToolInvocation(ANT, inv);
    }

    private void handleSbtBuild(InvocationBuilder builder, Path sbtFile) {
        //TODO: initial SBT support, needs more work
        Log.infof("Detected SBT build file %s", sbtFile);
        builder.addToolInvocation(SBT, List.of("--no-colors", "+publish"));
    }

    private void handleGradleBuild(InvocationBuilder builder, Path gradleFile, boolean skipTests) throws IOException {
        Log.infof("Detected Gradle build file %s", gradleFile);
        var optionalGradleVersion = GradleUtils
                .getGradleVersionFromWrapperProperties(GradleUtils.getPropertiesFile(gradleFile.getParent()));
        optionalGradleVersion.ifPresent(s -> builder.discoveredToolVersion(GRADLE, s));
        Log.infof("Detected Gradle version %s", optionalGradleVersion.orElse("none"));
        var specifiedJavaVersion = GradleUtils.getSpecifiedJavaVersion(gradleFile);

        if (!specifiedJavaVersion.isEmpty()) {
            Log.infof("Detected Java version %s in Gradle build file %s", specifiedJavaVersion, gradleFile);
            MavenJavaVersionDiscovery.filterJavaVersions(builder, specifiedJavaVersion);
        }
        ArrayList<String> inv = new ArrayList<>(GradleUtils.getGradleArgs(gradleFile));
        if (skipTests) {
            // We can't just add -x test as some tasks are derived from the Test task and not named test so
            // that exclusion would ignore them. Instead pass a property to activate
            // java-components/build-request-processor/src/main/resources/gradle/test.gradle
            inv.add("-DdisableTests");
        }
        if (GradleUtils.isInBuildGradle(gradleFile, GradleUtils.NEBULA_PLUGIN)) {
            Log.infof("Found Nebula plugin to add '-Prelease.version=%s'", version );
            inv.add("-Prelease.version=" + version);
        }
        if (GradleUtils.isInBuildGradle(gradleFile, GradleUtils.STAGE_VOTE_RELEASE_PLUGIN)) {
            Log.infof("Found StageVoteRelease plugin to add '-Prelease");
            inv.add("-Prelease");
        }

        final Collection<File> files = FileUtils.listFiles(
                gradleFile.getParent().toFile(),
                WildcardFileFilter.builder().setWildcards("*.gradle", "*.gradle.kts").get(),
                TrueFileFilter.INSTANCE);

        for (File buildFile : files) {
            try (Stream<String> lines = Files.lines(buildFile.toPath())) {
                if (lines.anyMatch(s -> s
                        .matches("(.*findProperty[(][\"']release[\"'].*|.*getProperty\\([\"']release[\"'].*|.*hasProperty[(][\"']release[\"'].*)"))) {
                    inv.add("-Prelease");
                    break;
                }
            }
        }

        //gradle projects often need plugins from the google repo
        //we add it by default
        builder.addRepository("google");
        builder.addToolInvocation(GRADLE, inv);
    }

    private void handleMavenBuild(InvocationBuilder builder, Path buildScript, boolean skipTests,
            CacheBuildInfoLocator buildInfoLocator) throws IOException, XmlPullParserException {
        Log.infof("Found Maven POM file at %s", buildScript);
        try (BufferedReader pomReader = Files.newBufferedReader(buildScript)) {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(pomReader);
            List<Model> models = new ArrayList<>();
            models.add(model);
            //TODO: we should do discovery on the whole tree
            if (model.getVersion() != null && model.getVersion().endsWith("-SNAPSHOT")) {
                //not tagged properly, deal with it automatically
                builder.enforceVersion("true");
            } else if (model.getVersion() == null || Objects.equals(version, model.getVersion())) {
                //if the version is null we can't run enforce version at this point
                //version is correct, don't run enforce version as it can fail on things
                //that are tagged correctly
                builder.versionCorrect();
            }

            MavenJavaVersionDiscovery.filterJavaVersions(buildScript, models, builder);
            if (builder.minJavaVersion != null && builder.maxJavaVersion != null) {
                if (builder.minJavaVersion.compareTo(builder.maxJavaVersion) > 0
                        || builder.maxJavaVersion.compareTo(builder.minJavaVersion) < 0) {
                    Log.warnf(
                            "Found incompatible Java versions in project with file %s: minJavaVersion: %s, maxJavaVersion: %s",
                            buildScript, builder.minJavaVersion, builder.maxJavaVersion);
                }
            }

            var invocations = new ArrayList<>(
                    List.of("install",
                            "-DallowIncompleteProjects",
                            "-Danimal.sniffer.skip", // https://github.com/mojohaus/animal-sniffer
                            "-Dcheckstyle.skip",
                            "-Dcobertura.skip",
                            "-Denforcer.skip",
                            "-Dformatter.skip", // https://code.revelc.net/formatter-maven-plugin/
                            "-Dgpg.skip",
                            "-Dimpsort.skip", // https://code.revelc.net/impsort-maven-plugin/plugin-info.html
                            "-Djapicmp.skip",
                            "-Dmaven.javadoc.failOnError=false",
                            "-Dmaven.site.deploy.skip",
                            "-Dpgpverify.skip",
                            "-Drat.skip=true",
                            "-Drevapi.skip",
                            "-Dsort.skip", // https://github.com/Ekryd/sortpom
                            "-Dspotbugs.skip",
                            "-Dspotless.check.skip=true" // https://github.com/diffplug/spotless/tree/main/plugin-maven
                    ));
            if (skipTests) {
                //we assume private repos are essentially fresh tags we have control of
                //so we should run the tests
                //this can be controller via additional args if you still want to skip them
                invocations.add("-DskipTests");
            }
            boolean releaseProfile = false;
            if (model.getProfiles() != null) {
                for (var profile : model.getProfiles()) {
                    if (Objects.equals(profile.getId(), "release")) {
                        invocations.add("-Prelease");
                        releaseProfile = true;
                    }
                }
            }
            builder.addToolInvocation(MAVEN, invocations);

            //look for repositories
            for (var repo : handleRepositories(buildScript, buildInfoLocator, releaseProfile)) {
                builder.addRepository(repo);
            }
        }
    }
}
