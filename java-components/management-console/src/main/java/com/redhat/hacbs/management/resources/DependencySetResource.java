package com.redhat.hacbs.management.resources;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.redhat.hacbs.management.dto.DependencySetDTO;
import com.redhat.hacbs.management.model.DependencySet;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.management.dto.IdentifiedDependencyDTO;
import com.redhat.hacbs.management.dto.ImageDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.ContainerImage;
import com.redhat.hacbs.resources.model.v1alpha1.JvmImageScan;
import com.redhat.hacbs.resources.model.v1alpha1.JvmImageScanSpec;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@Path("/dependency-set")
public class DependencySetResource {

    @GET
    @Path("{id}")
    public DependencySetDTO getDependencySet(@PathParam("id") long id) {
        DependencySet deps = DependencySet.findById(id);
        return new DependencySetDTO(id, deps.identifier, IdentifiedDependencyDTO.fromDependencySet(deps));
    }



}
