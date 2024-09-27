package com.redhat.hacbs.management.resources;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

import io.fabric8.kubernetes.client.KubernetesClient;

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

    public static class RepositoryPush {
        public String name;
        public String repository;
        public String namespace;
        public String docker_url;
        public String homepage;
        public List<String> updated_tags;
    }
}
