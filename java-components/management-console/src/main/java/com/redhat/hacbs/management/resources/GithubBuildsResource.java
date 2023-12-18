package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.GithubBuildDTO;
import com.redhat.hacbs.management.dto.IdentifiedDependencyDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.GithubActionsBuild;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
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
            List<IdentifiedDependencyDTO> depList = new ArrayList<>();

            //TODO: this is slow as hell
            for (var dep : build.dependencySet.dependencies) {
                Map<String, String> attributes = new HashMap<>();
                if (dep.attributes != null) {
                    for (var s : dep.attributes.split(";")) {
                        var parts = s.split("=");
                        attributes.put(parts[0], parts[1]);
                    }
                }
                Long buildId = null;
                boolean buildSuccess = false;
                if (dep.buildId == null) {
                    try {
                        StoredDependencyBuild db = (StoredDependencyBuild) entityManager.createQuery(
                                "select b from StoredArtifactBuild s inner join StoredDependencyBuild b on b.buildIdentifier=s.buildIdentifier where s.mavenArtifact = :artifact order by b.creationTimestamp desc")
                                .setParameter("artifact", dep.mavenArtifact)
                                .setMaxResults(1)
                                .getSingleResult();
                        buildId = db.id;
                        buildSuccess = db.succeeded;
                    } catch (NoResultException ignore) {
                    }
                } else {
                    BuildAttempt db = BuildAttempt.find("buildId", dep.buildId).singleResult();
                    if (db != null) {
                        buildId = db.dependencyBuild.id;
                        buildSuccess = db.dependencyBuild.succeeded;
                    }
                }
                var inQueue = false;
                Long n = (Long) entityManager.createQuery(
                        "select count(*) from BuildQueue b where b.mavenArtifact=:artifact")
                        .setParameter("artifact", dep.mavenArtifact)
                        .getSingleResult();
                if (n > 0) {
                    inQueue = true;
                }
                IdentifiedDependencyDTO d = new IdentifiedDependencyDTO(dep.mavenArtifact.gav(), dep.source,
                        buildId, inQueue, buildSuccess, attributes);
                depList.add(d);
            }
            Collections.sort(depList);

            GithubBuildDTO info = new GithubBuildDTO(build.dependencySet.identifier, build.complete, depList);

            ret.add(info);
        }
        return new PageParameters<>(ret, GithubActionsBuild.count(), page, perPage);
    }

}
