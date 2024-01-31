package com.redhat.hacbs.management.resources;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

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

@Path("/image")
public class ImageResource {
    @Inject
    Instance<KubernetesClient> kubernetesClient;

    @GET
    public PageParameters<ImageDTO> getImages(@QueryParam("page") int page, @QueryParam("perPage") int perPage) {
        if (perPage <= 0) {
            perPage = 20;
        }
        List<ContainerImage> all = ContainerImage
                .findAll(Sort.descending("timestamp"))
                .page(Page.of(page - 1, perPage)).list();
        return new PageParameters<>(
                all.stream().map(s -> new ImageDTO(s.repository.repository, s.tag, s.digest, s.analysisComplete,
                        IdentifiedDependencyDTO.fromDependencySet(s.dependencySet))).collect(Collectors.toList()),
                ContainerImage.count(), page, perPage);
    }

    @PUT
    public void image(String image) {
        JvmImageScan scan = new JvmImageScan();
        scan.setSpec(new JvmImageScanSpec());
        scan.getSpec().setImage(image);
        scan.setMetadata(new ObjectMeta());
        scan.getMetadata().setGenerateName("user-scan-");
        kubernetesClient.get().resource(scan).create();
    }
}
