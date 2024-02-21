package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.GithubBuildDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.GithubActionsBuild;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@Path("/builds/github")
public class GithubBuildsResource {

    @GET
    public PageParameters<GithubBuildDTO> all(@QueryParam("page") int page, @QueryParam("perPage") int perPage) {
        if (perPage <= 0) {
            perPage = 20;
        }

        List<GithubBuildDTO> ret = new ArrayList<>();
        List<GithubActionsBuild> builds = GithubActionsBuild.<GithubActionsBuild> find("", Sort.descending("creationTime"))
                .page(Page.of(page - 1, perPage)).list();
        for (var build : builds) {
            GithubBuildDTO info = new GithubBuildDTO(build.id, build.dependencySet.identifier, build.complete, build.prUrl,
                    build.dependencySet.id, build.buildDependencySet == null ? -1 : build.buildDependencySet.id);
            ret.add(info);
        }
        return new PageParameters<>(ret, GithubActionsBuild.count(), page, perPage);
    }

    @GET
    @Path("id/{id}")
    public GithubBuildDTO find(@PathParam("id") long id) {
        GithubActionsBuild build = GithubActionsBuild.findById(id);
        if (build == null) {
            throw new NotFoundException();
        }
        return new GithubBuildDTO(build.id, build.dependencySet.identifier, build.complete, build.prUrl,
                build.dependencySet.id, build.buildDependencySet == null ? -1 : build.buildDependencySet.id);
    }

}
