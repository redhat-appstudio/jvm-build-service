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
import org.kohsuke.github.function.InputStreamFunction;

import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.DependencySet;
import com.redhat.hacbs.management.model.GithubActionsBuild;
import com.redhat.hacbs.management.model.IdentifiedDependency;
import com.redhat.hacbs.management.model.MavenArtifact;
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
import io.vertx.ext.web.RoutingContext;

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

    @Inject
    RoutingContext routingContext;

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

        for (var check : pullRequest.getRepository().getCheckRuns(pullRequest.getPullRequest().getHead().getSha())) {
            //already exists
            if (check.getName().equals(SUPPLY_CHAIN_CHECK)) {
                return;
            }
        }

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
        List<GHArtifact> sbomList = wfr.listArtifacts().toList().stream().filter(s -> s.getName().contains("sbom.json"))
                .toList();
        if (sbomList.isEmpty()) {
            Log.infof("No artifacts found, waiting to see if they appear");
            for (var i = 1; i < 5; ++i) {
                try {
                    Thread.sleep(i * 1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                sbomList = wfr.listArtifacts().toList().stream().filter(s -> s.getName().equals("sbom.json")).toList();
                if (!sbomList.isEmpty()) {
                    break;
                }
            }
        }
        if (sbomList.isEmpty()) {
            return;
        }

        var conclusion = GHCheckRun.Conclusion.SUCCESS;
        List<String> failureList = new ArrayList<>();
        GithubActionsBuild githubBuild = GithubActionsBuild.find("workflowRunId", wfr.getId()).firstResult();
        if (githubBuild == null) {
            githubBuild = new GithubActionsBuild();
            githubBuild.creationTime = wfr.getRunStartedAt().toInstant();
        }
        if (githubBuild.dependencySet == null) {
            githubBuild.dependencySet = new DependencySet();
            githubBuild.dependencySet.dependencies = new ArrayList<>();
        } else {
            for (var i : githubBuild.dependencySet.dependencies) {
                i.delete();
            }
            githubBuild.dependencySet.dependencies.clear();
        }
        if (githubBuild.buildDependencySet == null) {
            githubBuild.buildDependencySet = new DependencySet();
            githubBuild.buildDependencySet.dependencies = new ArrayList<>();
        } else {
            for (var i : githubBuild.buildDependencySet.dependencies) {
                i.delete();
            }
            githubBuild.buildDependencySet.dependencies.clear();
        }
        githubBuild.commit = wfr.getHeadSha();

        githubBuild.workflowRunId = wfr.getId();
        Map<String, List<String>> successList = new HashMap<>();
        for (var artifact : sbomList) {
            Log.infof("Examining artifact %s", artifact.getName());
            boolean build = artifact.getName().equals("build-sbom.json");
            Bom sbom = artifact.download(new InputStreamFunction<Bom>() {
                @Override
                public Bom apply(InputStream input) throws IOException {
                    try (ZipInputStream zip = new ZipInputStream(input)) {
                        var entry = zip.getNextEntry();
                        while (entry != null) {
                            if (entry.getName().contains("sbom.json")) {
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
            for (var pr : wfr.getPullRequests()) {
                githubBuild.prUrl = pr.getHtmlUrl().toExternalForm();
            }
            githubBuild.repository = wfr.getRepository().getOwnerName() + "/" + wfr.getRepository().getName();
            githubBuild.dependencySet.type = "github-actions";
            githubBuild.buildDependencySet.type = "github-actions-build-dependencies";

            String identifier = githubBuild.repository + "#" + wfr.getId() + "@" + githubBuild.commit;
            githubBuild.dependencySet.identifier = identifier;
            githubBuild.buildDependencySet.identifier = identifier + "-build-dependencies";

            DependencySet dependencySet = build ? githubBuild.buildDependencySet : githubBuild.dependencySet;

            dependencySet.persistAndFlush();
            for (var i : sbom.getComponents()) {
                String gav = i.getGroup() + ":" + i.getName() + ":" + i.getVersion();

                IdentifiedDependency dep = new IdentifiedDependency();
                dep.mavenArtifact = MavenArtifact.forGav(gav);
                dep.dependencySet = dependencySet;
                dep.source = i.getPublisher();
                dependencySet.dependencies.add(dep);
                if (!Objects.equals(i.getPublisher(), "rebuilt") && !Objects.equals(i.getPublisher(), "redhat")) {
                    if (!build) {
                        conclusion = GHCheckRun.Conclusion.FAILURE;
                        failureList.add(gav);
                        BuildQueue.create(gav, true, Map.of("Github Build", identifier));
                    }

                    dep.source = "unknown";
                } else {
                    if (i.getProperties() != null) {
                        for (var attr : i.getProperties()) {
                            if (attr.getName().equals("java:build-id")) {
                                dep.buildId = attr.getValue();
                            } else if (attr.getName().equals("java:shaded-into")) {
                                dep.shadedInto = attr.getValue();
                            }
                        }
                    }
                    if (!build) {
                        successList.computeIfAbsent(i.getPublisher(), s -> new ArrayList<>())
                                .add(gav);
                    }
                }
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

        githubBuild.persistAndFlush();
        var output = new GHCheckRunBuilder.Output(
                failureList.size() > 0 ? "Build Contained Untrusted Dependencies" : "All dependencies are trusted",
                finalResult.toString());
        checkRun.update().withConclusion(conclusion).add(output)
                .withDetailsURL("https://jvmshield.dev/builds/github/build/" + githubBuild.id)
                .withStatus(GHCheckRun.Status.COMPLETED).create();
    }
}
