package com.redhat.hacbs.management.resources;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.ContainerImageRepository;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@Path("/image-repository")
public class ImageRepositoryResource {

    @GET
    public PageParameters<String> getRepositories(@QueryParam("page") int page, @QueryParam("perPage") int perPage) {
        if (perPage <= 0) {
            perPage = 20;
        }
        List<ContainerImageRepository> all = ContainerImageRepository
                .findAll(Sort.descending("repository"))
                .page(Page.of(page - 1, perPage)).list();
        return new PageParameters<>(
                all.stream().map(s -> s.repository).toList(),
                ContainerImageRepository.count(), page, perPage);
    }

}
