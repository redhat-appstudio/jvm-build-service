package com.redhat.hacbs.management.resources;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.resources.model.v1alpha1.JvmImageScan;
import com.redhat.hacbs.resources.model.v1alpha1.JvmImageScanSpec;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;

@Path("quay")
public class QuayResource {

    //    {
    //        "name": "repository",
    //        "repository": "mynamespace/repository",
    //        "namespace": "mynamespace",
    //        "docker_url": "quay.io/mynamespace/repository",
    //        "homepage": "https://quay.io/repository/mynamespace/repository",
    //        "updated_tags": [
    //        "latest"
    //  ]
    //    }

    @Inject
    KubernetesClient client;

    @POST
    public void handleQuayPush(RepositoryPush push) {
        Log.infof("handling quay messages for %s", push.repository);
        for (var tag : push.updated_tags) {
            Log.infof("handling quay messages for %s and tag %s", push.repository, tag);
            JvmImageScan scan = new JvmImageScan();
            scan.setSpec(new JvmImageScanSpec());
            scan.getSpec().setImage("quay.io/" + push.repository + ":" + tag);
            scan.setMetadata(new ObjectMeta());
            scan.getMetadata().setGenerateName("quay-push-scan-");
            client.resource(scan).create();
        }
    }

    public static class RepositoryPush {
        public String name;
        public String repository;
        public String namespace;
        public String docker_url;
        public String homepage;
        public List<String> updated_tags;
    }
}
