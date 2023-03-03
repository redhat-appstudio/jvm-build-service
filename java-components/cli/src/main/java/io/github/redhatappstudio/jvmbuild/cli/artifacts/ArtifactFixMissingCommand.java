package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.location.AddRecipeRequest;
import com.redhat.hacbs.recipies.location.ArtifactInfoRequest;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;
import com.redhat.hacbs.recipies.scm.RepositoryInfo;
import com.redhat.hacbs.recipies.scm.ScmInfo;
import com.redhat.hacbs.recipies.scm.TagMapping;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.util.GithubCredentials;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine;

@CommandLine.Command(name = "fix-missing")
public class ArtifactFixMissingCommand implements Runnable {

    @CommandLine.Option(names = "-g", description = "The artifact to rebuild, specified by GAV", completionCandidates = MissingGavCompleter.class)
    String targetGav;

    @CommandLine.Option(names = "-a", description = "The artifact to rebuild, specified by ArtifactBuild name", completionCandidates = MissingArtifactBuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "--group-level", description = "Apply this SCM information at the group level")
    boolean group;

    @CommandLine.Option(names = "--version-level", description = "Apply this SCM information at to a specific version")
    boolean version;

    @CommandLine.Option(names = "--uri", description = "The SCM URI")
    String uri;

    @CommandLine.Option(names = "--legacy", description = "Add this information to the legacyRepos section of the file")
    boolean legacy;

    @CommandLine.Option(names = "--path", description = "The path into the repo")
    String path;

    @CommandLine.Option(names = "--tag-mapping", description = "A tag mapping in the form of pattern=tag")
    String tagMapping;

    @Override
    public void run() {
        if (legacy && uri == null) {
            System.err.println("You must specify a URI when using --legacy");
            Quarkus.asyncExit(1);
            return;
        }
        if (tagMapping != null) {
            if (!tagMapping.contains("=")) {
                System.err.println("Tag mapping must be in the form of pattern=tag");
                Quarkus.asyncExit(1);
                return;
            }
        }
        var client = Arc.container().instance(KubernetesClient.class).get();
        String gav = null;
        if (artifact != null) {
            if (targetGav != null) {
                throwUnspecified();
            }
            ArtifactBuild artifactBuild = ArtifactBuildCompleter.createNames().get(artifact);
            if (artifactBuild == null) {
                throw new RuntimeException("Artifact not found: " + artifact);
            }
            gav = artifactBuild.getSpec().getGav();
        } else if (targetGav != null) {
            gav = targetGav;
        } else {
            throw new RuntimeException("Must specify one of -a or -g");
        }

        //TODO: should not be hard coded
        try {
            var gh = GitHub.connect();
            var me = gh.getMyself().getLogin();
            System.out.println(me);
            GHRepository mainRepo = gh.getRepository("redhat-appstudio/jvm-build-data");
            var forks = mainRepo.listForks();
            GHRepository myfork = null;
            for (GHRepository i : forks.toList()) {
                if (i.getOwnerName().equals(me)) {
                    myfork = i;
                    break;
                }
            }
            if (myfork == null) {
                throw new RuntimeException("Could not find fork of the redhat-appstudio/jvm-build-data repo owned by" + me
                        + ", please fork the repo");
            }
            Path checkoutPath = Files.createTempDirectory("fix-missing-recipe");
            try (Git checkout = Git.cloneRepository().setDirectory(checkoutPath.toFile())
                    .setURI(myfork.getHttpTransportUrl()).call()) {
                System.out.println(checkoutPath);
                String branchName = gav.replace(":", "-");
                checkout.checkout().setCreateBranch(true).setName(branchName)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM).setForceRefUpdate(true).call();
                RecipeLayoutManager recipeLayoutManager = new RecipeLayoutManager(checkoutPath);
                RecipeGroupManager groupManager = new RecipeGroupManager(List.of(recipeLayoutManager));
                GAV parsed = GAV.parse(gav);
                Set<GAV> locationRequests = Set.of(parsed);
                var existing = groupManager
                        .requestArtifactInformation(new ArtifactInfoRequest(locationRequests, Set.of(BuildRecipe.SCM)));
                if (!legacy) {
                    var existingModule = existing.getRecipes().get(parsed);
                    ScmInfo scmInfo = null;
                    Path existingFile = null;
                    if (existingModule != null && existingModule.containsKey(BuildRecipe.SCM)) {
                        existingFile = existingModule.get(BuildRecipe.SCM);
                        scmInfo = BuildRecipe.SCM.getHandler().parse(existingFile);
                        if (scmInfo.getUri().equals(uri)) {
                            System.err.println("Provided URI matches existing URI");
                        }
                        if (uri != null) {
                            scmInfo.setUri(uri);
                        }
                        if (path != null) {
                            scmInfo.setPath(path.equals("/") ? null : path);
                        }
                    } else {
                        if (uri == null) {
                            throw new RuntimeException("URI not specified, and no existing information");
                        }
                        scmInfo = new ScmInfo("git", uri, path);
                    }
                    handleTagMapping(tagMapping, scmInfo);
                    if (existingFile != null && uri == null) {
                        BuildRecipe.SCM.getHandler().write(scmInfo, existingFile);
                    } else {
                        recipeLayoutManager.writeArtifactData(new AddRecipeRequest<>(BuildRecipe.SCM, scmInfo,
                                parsed.getGroupId(), group ? null : parsed.getArtifactId(),
                                version ? parsed.getVersion() : null));
                    }

                } else {
                    //legacy mode, we just want to add legacy info to an existing file
                    var existingModule = existing.getRecipes().get(parsed);
                    if (existingModule == null || !existingModule.containsKey(BuildRecipe.SCM)) {
                        System.err.println("Cannot use --legacy when there is no existing data");
                        Quarkus.asyncExit(1);
                        return;
                    }
                    Path existingFile = existingModule.get(BuildRecipe.SCM);
                    ScmInfo existingInfo = BuildRecipe.SCM.getHandler().parse(existingFile);
                    RepositoryInfo repo = null;
                    for (var existingLegacy : existingInfo.getLegacyRepos()) {
                        if (existingLegacy.getUri().equals(uri)) {
                            repo = existingInfo;
                            System.err.println("Legacy repo already exists");
                            break;
                        }
                    }
                    if (repo == null) {
                        existingInfo.getLegacyRepos().add(repo = new RepositoryInfo("git", uri, path));
                    }
                    handleTagMapping(tagMapping, repo);
                    BuildRecipe.SCM.getHandler().write(existingInfo, existingFile);
                }
                //commit the changes
                checkout.add().addFilepattern("scm-info").call();
                checkout.commit().setMessage("Add scm-info for " + gav)
                        .setAll(true)
                        .call();

                //push the changes to our fork
                checkout.push().setForce(true).setCredentialsProvider(new GithubCredentials(me)).setRemote("origin").call();

                String head = me + ":" + branchName;
                System.out.println("head:" + head);
                mainRepo.createPullRequest("Add scm-info for " + gav, head, "main", "");

            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private void handleTagMapping(String tagMapping, RepositoryInfo repo) {
        if (tagMapping == null) {
            return;
        }
        String[] parts = tagMapping.split("=");
        if (parts.length != 2) {
            throw new RuntimeException("Tag mapping must be in the form pattern=tag");
        }
        if (repo.getTagMapping() == null) {
            repo.setTagMapping(new ArrayList<>());
        }
        TagMapping tm = new TagMapping();
        tm.setPattern(parts[0]);
        tm.setTag(parts[1]);
        repo.getTagMapping().add(tm);
    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -a, -g or --failed");
    }
}
