package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.GRADLE;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.JDK;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.GOOGLE_JAVA_FORMAT_PLUGIN;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.jgit.api.Git;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.container.analyser.build.gradle.GradleUtils;
import com.redhat.hacbs.container.analyser.build.maven.MavenDiscoveryTask;
import com.redhat.hacbs.container.analyser.location.VersionRange;
import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.location.BuildInfoRequest;
import com.redhat.hacbs.recipies.location.RecipeDirectory;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeRepositoryManager;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "lookup-build-info")
public class LookupBuildInfoCommand implements Runnable {

    @CommandLine.Option(names = "--recipes", required = true, split = ",")
    List<String> recipeRepos;

    @CommandLine.Option(names = "--scm-url", required = true)
    String scmUrl;

    @CommandLine.Option(names = "--scm-tag", required = true)
    String tag;

    @CommandLine.Option(names = "--context", required = true)
    String context;

    @CommandLine.Option(names = "--version", required = true)
    String version;

    @CommandLine.Option(names = "--message")
    Path message;

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
            //checkout the git recipe database
            List<RecipeDirectory> managers = new ArrayList<>();
            for (var i : recipeRepos) {
                Path tempDir = Files.createTempDirectory("recipe");
                managers.add(RecipeRepositoryManager.create(i, "main", Optional.empty(), tempDir));
            }
            RecipeGroupManager recipeGroupManager = new RecipeGroupManager(managers);

            var result = recipeGroupManager
                    .requestBuildInformation(new BuildInfoRequest(scmUrl, tag, Set.of(BuildRecipe.BUILD)));

            BuildRecipeInfo buildRecipeInfo = null;
            if (result.getData().containsKey(BuildRecipe.BUILD)) {
                buildRecipeInfo = BuildRecipe.BUILD.getHandler().parse(result.getData().get(BuildRecipe.BUILD));
            }

            doBuildAnalysis(scmUrl, tag, context, buildRecipeInfo, version);

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

    private void doBuildAnalysis(String scmUrl, String scmTag, String context, BuildRecipeInfo buildRecipeInfo, String versions)
            throws Exception {
        //TODO: this is a basic hack to prove the concept
        var path = Files.createTempDirectory("checkout");
        try (var clone = Git.cloneRepository().setURI(scmUrl).setDirectory(path.toFile()).call()) {
            clone.checkout().setName(scmTag).call();
            long time = clone.getRepository().parseCommit(clone.getRepository().resolve(scmTag)).getCommitTime() * 1000L;
            if (context != null) {
                path = path.resolve(context);
            }
            BuildInfo info = new BuildInfo();
            info.commitTime = time;
            Path pomFile = path.resolve("pom.xml");
            if (Files.isRegularFile(pomFile)) {
                try (BufferedReader pomReader = Files.newBufferedReader(pomFile)) {
                    MavenXpp3Reader reader = new MavenXpp3Reader();
                    Model model = reader.read(pomReader);
                    List<DiscoveryResult> results = new ArrayList<>();
                    results.add(new DiscoveryResult(
                            Map.of(JDK, new VersionRange("8", "17", "11"), MAVEN, new VersionRange("3.8", "3.8", "3.8")),
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
                    Collections.sort(results);
                    for (var i : results) {
                        info.tools.putAll(i.toolVersions);
                    }
                    info.invocations.add(
                            new ArrayList<>(List.of("clean", "install", "-DskipTests", "-Denforcer.skip", "-Dcheckstyle.skip",
                                    "-Drat.skip=true", "-Dmaven.deploy.skip=false", "-Dgpg.skip", "-Drevapi.skip",
                                    "-Djapicmp.skip")));
                }
            } else if (GradleUtils.isGradleBuild(path)) {
                Log.infof("Detected Gradle build in %s", path);
                var optionalGradleVersion = GradleUtils
                        .getGradleVersionFromWrapperProperties(GradleUtils.getPropertiesFile(path));
                var detectedGradleVersion = optionalGradleVersion.orElse("7");
                Log.infof("Detected Gradle version %s",
                        optionalGradleVersion.isPresent() ? detectedGradleVersion : "none");
                Log.infof("Chose Gradle version %s", detectedGradleVersion);
                var javaVersion = GradleUtils.getSupportedJavaVersion(detectedGradleVersion);
                Log.infof("Chose Java version %s based on Gradle version detected", javaVersion);

                if (GradleUtils.isInBuildGradle(path, GOOGLE_JAVA_FORMAT_PLUGIN)) {
                    javaVersion = "11";
                    Log.infof("Detected %s in build files and set Java version to %s", GOOGLE_JAVA_FORMAT_PLUGIN,
                            javaVersion);
                }

                info.tools.put(JDK, new VersionRange("8", "17", javaVersion));
                info.tools.put(GRADLE, new VersionRange(detectedGradleVersion, detectedGradleVersion, detectedGradleVersion));
                info.invocations.add(new ArrayList<>(GradleUtils.getGradleArgs(path)));
                info.toolVersion = detectedGradleVersion;
                info.javaVersion = javaVersion;
            }
            if (buildRecipeInfo != null) {
                Log.infof("Got build recipe info %s", buildRecipeInfo);
                if (buildRecipeInfo.getAdditionalArgs() != null) {
                    for (var i : info.invocations) {
                        i.addAll(buildRecipeInfo.getAdditionalArgs());
                    }
                }
                if (buildRecipeInfo.isEnforceVersion()) {
                    info.enforceVersion = version;
                }
                info.setIgnoredArtifacts(buildRecipeInfo.getIgnoredArtifacts());
            }
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(buildInfo.toFile(), info);
        }
    }
}
