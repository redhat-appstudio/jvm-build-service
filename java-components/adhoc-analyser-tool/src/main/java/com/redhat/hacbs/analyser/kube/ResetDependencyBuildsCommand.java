package com.redhat.hacbs.analyser.kube;

import java.util.Collections;
import java.util.List;

import jakarta.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "reset-dependency-builds")
public class ResetDependencyBuildsCommand implements Runnable {

    @Inject
    KubernetesClient kubernetesClient;

    @CommandLine.Option(names = "-b", defaultValue = "")
    String build;
    @CommandLine.Option(names = "-f", defaultValue = "false")
    boolean failedOnly;

    @Override
    public void run() {
        try {
            MixedOperation<DependencyBuild, KubernetesResourceList<DependencyBuild>, Resource<DependencyBuild>> client = kubernetesClient
                    .resources(DependencyBuild.class);
            if (!build.isEmpty()) {
                DependencyBuild item = client.withName(build).get();
                item.getStatus().setState("");
                item.getStatus().setFailedBuildRecipes(Collections.emptyList());
                client.updateStatus(item);
            } else {
                List<DependencyBuild> items = client.list().getItems();
                for (var request : items) {
                    if (!failedOnly || request.getStatus().getState().equals("DependencyBuildStateFailed")) {
                        request.getStatus().setState("");
                        request.getStatus().setFailedBuildRecipes(Collections.emptyList());
                        client.updateStatus(request);
                    }
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
        }
    }
}
