package com.redhat.hacbs.management.github;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipInputStream;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.cyclonedx.BomParserFactory;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHArtifact;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCheckRunBuilder;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHWorkflowRun;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.function.InputStreamFunction;

import com.redhat.hacbs.management.model.*;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.event.PullRequest;
import io.quarkiverse.githubapp.event.WorkflowRun;
import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;

@Startup
@ApplicationScoped
public class GithubIntegration {

    public static final String SUPPLY_CHAIN_CHECK_DONE = "supply-chain-check-done";
    public static final String WORKFLOW_RUN_ID = "workflow-run-id";
    @Inject
    KubernetesClient client;

    @Inject
    GitHubClientProvider gitHub;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "kube.disabled", defaultValue = "false")
    boolean disabled;

    public static final String SUPPLY_CHAIN_CHECK = "Supply Chain Check";

    @PostConstruct
    public void setupWatch() {
        if ((LaunchMode.current() == LaunchMode.TEST
                && !Objects.equals(System.getProperty(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY), "test")) || disabled) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate GitHub integration");
            return;
        }
        client.resources(ArtifactBuild.class).watch(new Watcher<ArtifactBuild>() {
            @Override
            public void eventReceived(Action action, ArtifactBuild artifactBuild) {
                handleArtifactBuild(artifactBuild);
            }

            @Override
            public void onClose(WatcherException e) {

            }
        });
    }

    @Transactional
    void handleArtifactBuild(ArtifactBuild artifactBuild) {
        try {
            //ignore transient states
            if (artifactBuild.getStatus() == null ||
                    artifactBuild.getStatus().getState() == null ||
                    artifactBuild.getStatus().getState().isEmpty() ||
                    Objects.equals(artifactBuild.getStatus().getState(), ModelConstants.ARTIFACT_BUILD_DISCOVERING) ||
                    Objects.equals(artifactBuild.getStatus().getState(), ModelConstants.ARTIFACT_BUILD_BUILDING) ||
                    Objects.equals(artifactBuild.getStatus().getState(), ModelConstants.ARTIFACT_BUILD_NEW)) {
                return;
            }
            MavenArtifact a = MavenArtifact.forGav(artifactBuild.getSpec().getGav());
            List<Object[]> results = entityManager.createQuery(
                    "select i,g from GithubActionsBuild g inner join g.dependencySet d inner join d.dependencies i where not g.complete and i.buildId is null and i.mavenArtifact = :artifact")
                    .setParameter("artifact", a)
                    .getResultList();
            for (var r : results) {

                IdentifiedDependency id = (IdentifiedDependency) r[0];
                GithubActionsBuild b = (GithubActionsBuild) r[1];
                if (b.complete) {
                    continue;
                }
                id.buildComplete = true;
                id.buildSuccessful = artifactBuild.getStatus().getState().equals(ModelConstants.ARTIFACT_BUILD_COMPLETE);
                b.complete = true;
                for (var deps : b.dependencySet.dependencies) {
                    if (!deps.isTrusted() && !deps.buildComplete) {
                        b.complete = false;
                        break;
                    }
                }
                if (b.complete) {
                    String repoName = b.repository;
                    var parts = repoName.split("/");
                    var inst = gitHub.getApplicationClient().getApp().getInstallationByRepository(parts[0], parts[1]);
                    GitHub gitHub = GithubIntegration.this.gitHub.getInstallationClient(inst.getId());
                    GHCheckRun checkRun = null;
                    var repo = gitHub.getRepository(repoName);

                    for (var check : repo.getCheckRuns(b.commit)) {
                        if (check.getName().equals(SUPPLY_CHAIN_CHECK)) { //TODO: check app as well
                            checkRun = check;
                            break;
                        }
                    }
                    if (checkRun == null) {
                        return;
                    }
                    String summary = checkRun.getOutput().getSummary();
                    for (var i : b.dependencySet.dependencies) {
                        if (i.buildSuccessful) {
                            summary = summary.replace(i.mavenArtifact.gav() + "\n",
                                    i.mavenArtifact.gav() + "[Rebuild Successful]\n");
                        } else {
                            summary = summary.replace(i.mavenArtifact.gav() + "\n",
                                    i.mavenArtifact.gav() + "[Rebuild Failed]\n");
                        }
                    }
                    checkRun.update().add(new GHCheckRunBuilder.Output(checkRun.getOutput().getTitle(), summary)).create();
                    var wfr = repo.getWorkflowRun(b.workflowRunId);
                    wfr.rerun();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Transactional
    void onOpen(@PullRequest.Opened GHEventPayload.PullRequest pullRequest, GitHub gitHub) throws IOException {

        System.out.println("App: " + pullRequest.getInstallation().getAppId());
        System.out.println("Target: " + pullRequest.getInstallation().getTargetId());
        System.out.println(gitHub.getInstallation());
        var cr = pullRequest.getRepository().createCheckRun(SUPPLY_CHAIN_CHECK,
                pullRequest.getPullRequest().getHead().getSha());
        Log.infof("Creating check for for %s", pullRequest.getPullRequest().getHead().getSha());
        cr.withStatus(GHCheckRun.Status.QUEUED);
        cr.create();
    }

    @Transactional
    void onReopen(@PullRequest.Reopened GHEventPayload.PullRequest pullRequest, GitHub gitHub) throws IOException {
        onOpen(pullRequest, gitHub);
    }

    @Transactional
    void onSync(@PullRequest.Synchronize GHEventPayload.PullRequest pullRequest, GitHub gitHub) throws IOException {
        onOpen(pullRequest, gitHub);
    }

    @Transactional
    void onWorkflowCompleted(@WorkflowRun.Completed GHEventPayload.WorkflowRun wfr) throws IOException {
        handleWorkflowRun(wfr.getWorkflowRun());
    }

    void handleWorkflowRun(GHWorkflowRun wfr) throws IOException {

        Log.infof("Workflow completed for %s", wfr.getHeadSha());
        GHCheckRun checkRun = null;
        for (var check : wfr.getRepository().getCheckRuns(wfr.getHeadSha())) {
            if (check.getName().equals(SUPPLY_CHAIN_CHECK)) { //TODO: check app as well
                checkRun = check;
                break;
            }
        }
        if (checkRun == null) {
            Log.error("Check run not found");
            return;
        }
        PagedIterable<GHArtifact> artifacts = wfr.listArtifacts();
        for (var artifact : artifacts) {
            Log.infof("Examining artifact %s", artifact.getName());
            if (artifact.getName().equals("sbom.json")) {
                Log.infof("Found sbom.json");
                Bom sbom = artifact.download(new InputStreamFunction<Bom>() {
                    @Override
                    public Bom apply(InputStream input) throws IOException {
                        try (ZipInputStream zip = new ZipInputStream(input)) {
                            var entry = zip.getNextEntry();
                            while (entry != null) {
                                if (entry.getName().equals("sbom.json")) {
                                    byte[] dd = zip.readAllBytes();
                                    System.out.println(new String(dd));
                                    try {
                                        return BomParserFactory.createParser(dd).parse(dd);
                                    } catch (ParseException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                                entry = zip.getNextEntry();
                            }
                        }
                        return null;
                    }
                });
                Log.infof("Parsed SBOM");
                var conclusion = GHCheckRun.Conclusion.SUCCESS;
                List<String> failureList = new ArrayList<>();
                GithubActionsBuild githubBuild = GithubActionsBuild.find("workflowRunId", wfr.getId()).firstResult();
                if (githubBuild == null) {
                    githubBuild = new GithubActionsBuild();
                    githubBuild.creationTime = wfr.getRunStartedAt().toInstant();
                    githubBuild.dependencySet = new DependencySet();
                    githubBuild.dependencySet.dependencies = new ArrayList<>();
                }

                Map<String, List<String>> successList = new HashMap<>();
                for (var i : sbom.getComponents()) {
                    String gav = i.getGroup() + ":" + i.getName() + ":" + i.getVersion();
                    IdentifiedDependency dep = new IdentifiedDependency();
                    dep.mavenArtifact = MavenArtifact.forGav(gav);
                    dep.dependencySet = githubBuild.dependencySet;
                    dep.source = i.getPublisher();
                    githubBuild.dependencySet.dependencies.add(dep);
                    if (!Objects.equals(i.getPublisher(), "rebuilt") && !Objects.equals(i.getPublisher(), "redhat")) {
                        conclusion = GHCheckRun.Conclusion.FAILURE;
                        failureList.add(gav);
                        BuildQueue.create(gav, true, "Github Build");

                        dep.source = "unknown";
                    } else {
                        if (i.getProperties() != null) {
                            for (var attr : i.getProperties()) {
                                if (attr.getName().equals("java:build-id")) {
                                    dep.buildId = attr.getValue();
                                }
                            }
                        }
                        successList.computeIfAbsent(i.getPublisher(), s -> new ArrayList<>())
                                .add(gav);
                    }
                }
                StringBuilder finalResult = new StringBuilder();

                if (conclusion == GHCheckRun.Conclusion.FAILURE) {
                    finalResult.append(String.format("""
                            <details>
                            <summary>There are %s untrusted artifacts in the result</summary>

                            ```diff
                            """, failureList.size()));
                    for (var i : failureList) {
                        finalResult.append("- ").append(i).append("\n");
                    }
                    finalResult.append("```\n</details>");

                } else {
                    for (var e : successList.entrySet()) {
                        finalResult.append(String.format("""
                                <details>
                                <summary>There are %s artifacts from %s in the result/summary>

                                ```diff
                                """, e.getValue().size(), e.getKey()));
                        for (var i : e.getValue()) {
                            finalResult.append("+ ").append(i).append("\n");
                        }
                        finalResult.append("```\n</details>");
                    }
                }

                githubBuild.workflowRunId = wfr.getId();
                for (var pr : wfr.getPullRequests()) {
                    githubBuild.prUrl = pr.getUrl().toExternalForm();
                }
                githubBuild.commit = wfr.getHeadSha();
                githubBuild.repository = wfr.getRepository().getOwnerName() + "/" + wfr.getRepository().getName();
                githubBuild.dependencySet.identifier = githubBuild.repository + "#" + wfr.getId() + "@" + githubBuild.commit;
                githubBuild.dependencySet.type = "github-build";
                githubBuild.persistAndFlush();
                var output = new GHCheckRunBuilder.Output(
                        failureList.size() > 0 ? "Build Contained Untrusted Dependencies" : "All dependencies are trusted",
                        finalResult.toString());
                checkRun.update().withConclusion(conclusion).add(output).withStatus(GHCheckRun.Status.COMPLETED).create();
                break;
            }
        }
    }
}
