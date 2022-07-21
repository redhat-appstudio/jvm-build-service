package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.GRADLE;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.JDK;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.GOOGLE_JAVA_FORMAT_PLUGIN;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.api.Git;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.container.analyser.build.gradle.GradleUtils;
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

    @Override
    public void run() {
        try {
            Path tempDir = Files.createTempDirectory("recipe");
            //checkout the git recipe database
            List<RecipeDirectory> managers = new ArrayList<>();
            for (var i : recipeRepos) {
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
        try (var clone = Git.cloneRepository().setURI(scmUrl).setBranch(scmTag).setDirectory(path.toFile()).call()) {
            if (context != null) {
                path = path.resolve(context);
            }
            BuildInfo info = new BuildInfo();
            if (Files.isRegularFile(path.resolve("pom.xml"))) {
                info.tools.put(JDK, new VersionRange("8", "17", "11"));
                info.tools.put(MAVEN, new VersionRange("3.8", "3.8", "3.8"));
                info.invocations.add(
                        new ArrayList<>(List.of("clean", "install", "-DskipTests", "-Denforcer.skip", "-Dcheckstyle.skip",
                                "-Drat.skip=true", "-Dmaven.deploy.skip=false")));
            } else if (GradleUtils.isGradleBuild()) {
                var optionalGradleVersion = GradleUtils.getGradleVersionFromWrapperProperties();
                var gradleVersion = optionalGradleVersion.orElse(GradleUtils.DEFAULT_GRADLE_VERSION);
                var javaVersion = GradleUtils.getSupportedJavaVersion(gradleVersion);

                if (GradleUtils.isInBuildGradle(GOOGLE_JAVA_FORMAT_PLUGIN)) {
                    javaVersion = "11";
                }

                info.tools.put(JDK, new VersionRange("8", "17", javaVersion));
                info.tools.put(GRADLE, new VersionRange(gradleVersion, gradleVersion, gradleVersion));
                info.invocations.add(new ArrayList<>(GradleUtils.DEFAULT_GRADLE_ARGS));
                info.toolVersion = gradleVersion;
                info.javaHome = "/usr/lib/jvm/java-" + ("8".equals(javaVersion) ? "1.8.0" : javaVersion) + "-openjdk";
            }
            if (buildRecipeInfo != null) {
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
