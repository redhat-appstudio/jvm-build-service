package com.redhat.hacbs.container.analyser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.eclipse.jgit.api.Git;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.location.ProjectBuildRequest;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeRepositoryManager;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequest;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequestStatus;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.logging.Log;
import io.quarkus.runtime.util.HashUtil;
import picocli.CommandLine;

@CommandLine.Command
public class ProcessCommand implements Runnable {

    @Inject
    KubernetesClient kubernetesClient;

    @CommandLine.Parameters(index = "0")
    String recipeRepo;

    @Override
    public void run() {
        try {
            Path tempDir = Files.createTempDirectory("recipe");
            RecipeRepositoryManager manager = RecipeRepositoryManager.create(recipeRepo, "main", Optional.empty(), tempDir);
            RecipeGroupManager recipeGroupManager = new RecipeGroupManager(List.of(manager));
            Map<GAV, ArtifactBuildRequest> gavs = new HashMap<>();
            Map<String, Map<GAV, ArtifactBuildRequest>> scmInfo = new HashMap<>();
            //TODO: for now we just process every item
            Set<GAV> toBuild = new HashSet<>();
            Log.infof("Processing requests");
            for (var request : kubernetesClient.resources(ArtifactBuildRequest.class).list().getItems()) {
                GAV gav = GAV.parse(request.getSpec().getGav());
                toBuild.add(gav);
                gavs.put(gav, request);
                request.getStatus().setState(ArtifactBuildRequestStatus.State.MISSING);
            }
            var result = recipeGroupManager.requestBuildInformation(new ProjectBuildRequest(toBuild, Set.of(BuildRecipe.SCM)));
            for (var e : gavs.entrySet()) {
                Log.infof("Processing %s", e.getKey());
                var recipes = result.getRecipes().get(e.getKey());
                var scm = recipes == null ? null : recipes.get(BuildRecipe.SCM);
                Log.infof("Found %s %s", recipes, scm);
                if (recipes == null || scm == null) {
                    e.getValue().getStatus().setState(ArtifactBuildRequestStatus.State.MISSING);
                    kubernetesClient.resources(ArtifactBuildRequest.class).updateStatus(e.getValue());
                    continue;
                }
                try {
                    scmInfo.computeIfAbsent(BuildRecipe.SCM.getHandler().parse(scm).getUri(), (key) -> new HashMap<>())
                            .put(e.getKey(), e.getValue());
                } catch (Exception ex) {
                    Log.errorf(ex, "Failed to parse %s", scm);
                }
            }
            for (var e : scmInfo.entrySet()) {
                try {
                    DependencyBuild build = new DependencyBuild();
                    build.getSpec().setScmUrl(e.getKey());
                    build.getSpec().setScmType("git"); //todo multiple SCMs
                    //turn the gav into a unique name that satisfies the name rules
                    //we could just hash it, but these names are easier for humans
                    String version = e.getValue().keySet().iterator().next().getVersion();
                    String basicName = e.getKey() + "." + version;
                    String hash = HashUtil.sha1(basicName);
                    StringBuilder newName = new StringBuilder();
                    boolean lastDot = false;
                    for (var i : e.getKey().toCharArray()) {
                        if (Character.isAlphabetic(i) || Character.isDigit(i)) {
                            newName.append(Character.toLowerCase(i));
                            lastDot = false;
                        } else {
                            if (!lastDot) {
                                newName.append('.');
                            }
                            lastDot = true;
                        }
                    }
                    String repoName = newName.toString();
                    newName.append("-");
                    newName.append(hash);
                    build.getMetadata().setName(newName.toString());
                    try {
                        String selectedTag = null;
                        Set<String> exactContains = new HashSet<>();
                        var tags = Git.lsRemoteRepository().setRemote(e.getKey()).setTags(true).setHeads(false).call();
                        for (var tag : tags) {
                            String name = tag.getName().replace("refs/tags/", "");
                            if (name.equals(version)) {
                                selectedTag = version;
                                break;
                            } else if (name.contains(version)) {
                                exactContains.add(tag.getName());
                            }
                        }
                        Log.infof("Found tag %s for %s", selectedTag, e.getKey());
                        if (selectedTag == null) {
                            if (exactContains.size() == 1) {
                                selectedTag = exactContains.iterator().next();
                            } else {
                                RuntimeException runtimeException = new RuntimeException(
                                        "Could not determine tag for " + version);
                                runtimeException.setStackTrace(new StackTraceElement[0]);
                                throw runtimeException;
                            }
                        }
                        build.getSpec().setTag(selectedTag);
                    } catch (Exception ex) {
                        Log.errorf(ex, "Failed to determine tag for %s", e.getKey());
                        for (var ar : e.getValue().values()) {
                            ar.getStatus().setState(ArtifactBuildRequestStatus.State.MISSING); //TODO: is missing correct?
                            ar.getStatus().setMessage("Failed to determine tag to use for build: " + ex.getMessage());
                            kubernetesClient.resources(ArtifactBuildRequest.class).updateStatus(ar);
                        }
                        continue;
                    }
                    kubernetesClient.resources(DependencyBuild.class).create(build);
                    for (var ar : e.getValue().values()) {
                        ar.getStatus().setState(ArtifactBuildRequestStatus.State.BUILDING); //TODO: is missing correct?
                        ar.getStatus().setMessage(null);
                        kubernetesClient.resources(ArtifactBuildRequest.class).updateStatus(ar);
                    }
                    //Now try and build it
                    //TODO: this really does not belong here
                    var run = kubernetesClient.genericKubernetesResources("tekton.dev/v1beta1", "PipelineRun");
                    GenericKubernetesResource pr = new GenericKubernetesResource();
                    pr.setKind("PipelineRun");
                    pr.setApiVersion("tekton.dev/v1beta1");
                    pr.setMetadata(new ObjectMeta());
                    pr.getMetadata().setAdditionalProperty("generateName", repoName + "-");
                    Map<String, Object> props = new HashMap<>();
                    pr.setAdditionalProperties(props);
                    Map<String, Object> spec = new HashMap<>();
                    props.put("spec", spec);
                    spec.put("pipelineRef", Map.of("name", "run-component-build"));
                    spec.put("params", List.of(
                            Map.of("name", "url", "value", build.getSpec().getScmUrl()),
                            Map.of("name", "tag", "value", build.getSpec().getTag())));
                    spec.put("workspaces",
                            List.of(
                                    Map.of("name", "maven-settings", "emptyDir", Map.of()),
                                    Map.of("name", "shared-workspace", "volumeClaimTemplate",
                                            Map.of("spec",
                                                    Map.of("accessModes", List.of("ReadWriteOnce"),
                                                            "resources", Map.of("requests", Map.of("storage", "1Gi")))))));
                    var prRes = run.create(pr);
                    Log.infof("Created %s", pr);
                } catch (KubernetesClientException ex) {
                    Status status = ex.getStatus();
                    if (status == null || status.getReason() == null || !status.getReason().equals("AlreadyExists")) {
                        Log.errorf(ex, "Failed to process build request for %s", e.getKey());
                    }
                } catch (Exception ex) {
                    Log.errorf(ex, "Failed to process build request for %s", e.getKey());
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
        }
    }

    public void rebuild(Set<String> gavs) {
        Log.infof("Identified Community Dependencies: %s", gavs);
        //know we know which community dependencies went into the build

        //now use the kube client to stick it into a CR to signify that these dependencies should be built
        for (var gav : gavs) {
            try {
                StringBuilder newName = new StringBuilder();
                //turn the gav into a unique name that satisfies the name rules
                //we could just hash it, but these names are easier for humans
                for (var i : gav.toCharArray()) {
                    if (Character.isAlphabetic(i) || Character.isDigit(i) || i == '.') {
                        newName.append(Character.toLowerCase(i));
                    } else {
                        newName.append(".br.");
                    }
                }
                ArtifactBuildRequest item = new ArtifactBuildRequest();
                ObjectMeta objectMeta = new ObjectMeta();
                objectMeta.setName(newName.toString());
                objectMeta.setAdditionalProperty("gav", gav);
                item.setMetadata(objectMeta);
                item.getSpec().setGav(gav);
                item.setKind(ArtifactBuildRequest.class.getSimpleName());
                kubernetesClient.resources(ArtifactBuildRequest.class).create(item);
            } catch (KubernetesClientException e) {
                if (!e.getStatus().getReason().equals("AlreadyExists")) {
                    throw e;
                }
            }
        }
    }

}
