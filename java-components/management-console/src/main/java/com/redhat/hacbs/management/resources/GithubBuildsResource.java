package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.GithubBuildDTO;
import com.redhat.hacbs.management.dto.IdentifiedDependencyDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.GithubActionsBuild;
import com.redhat.hacbs.management.watcher.DeploymentWatcher;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@Path("/builds/github")
public class GithubBuildsResource {

    @Inject
    DeploymentWatcher deploymentWatcher;

    @Inject
    EntityManager entityManager;

    @GET
    public PageParameters<GithubBuildDTO> all(@QueryParam("page") int page, @QueryParam("perPage") int perPage) {
        if (perPage <= 0) {
            perPage = 20;
        }

        List<GithubBuildDTO> ret = new ArrayList<>();
        List<GithubActionsBuild> builds = GithubActionsBuild.<GithubActionsBuild> find("", Sort.descending("creationTime"))
                .page(Page.of(page - 1, perPage)).list();
        for (var build : builds) {
            List<IdentifiedDependencyDTO> depList = IdentifiedDependencyDTO.fromDependencySet(build.dependencySet);
            GithubBuildDTO info = new GithubBuildDTO(build.dependencySet.identifier, build.complete, depList);

            ret.add(info);
        }
        return new PageParameters<>(ret, GithubActionsBuild.count(), page, perPage);
    }

}
