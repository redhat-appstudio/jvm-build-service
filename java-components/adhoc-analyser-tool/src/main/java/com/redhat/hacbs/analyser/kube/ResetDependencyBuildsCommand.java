package com.redhat.hacbs.analyser.kube;

import java.util.List;

import javax.inject.Inject;

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

    @Override
    public void run() {
        try {
            MixedOperation<DependencyBuild, KubernetesResourceList<DependencyBuild>, Resource<DependencyBuild>> client = kubernetesClient
                    .resources(DependencyBuild.class);
            if (!build.isEmpty()) {
                DependencyBuild item = client.withName(build).get();
                item.getStatus().setState("");
                client.updateStatus(item);
            } else {
                List<DependencyBuild> items = client.list().getItems();
                for (var request : items) {
                    request.getStatus().setState("");
                    client.updateStatus(request);
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
        }
    }
}
