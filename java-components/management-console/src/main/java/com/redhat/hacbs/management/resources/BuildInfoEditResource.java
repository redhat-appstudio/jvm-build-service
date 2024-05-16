package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.common.tools.repo.BuildInfoService;
import com.redhat.hacbs.management.dto.BuildAttemptDTO;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.recipes.build.BuildRecipeInfo;

@Path("/build-info/edit")
public class BuildInfoEditResource {

    @GET
    public BuildInfoService.BuildEditInfo get(@QueryParam("gav") String scmUri) {
        Optional<BuildInfoService.BuildEditInfo> scmInfo = BuildInfoService.getBuildInfo(scmUri);
        if (scmInfo.isPresent()) {
            return scmInfo.get();
        }
        return new BuildInfoService.BuildEditInfo(new BuildRecipeInfo(), scmUri, false);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public EditResult modify(BuildInfoService.BuildEditInfo command) {
        var prUrul = BuildInfoService.writeBuildInfo(command);
        EditResult ret = new EditResult();
        ret.prUrl = prUrul;
        return ret;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    @Path("approve-validation")
    public EditResult modify(BuildAttemptDTO buildAttemptDTO) {
        BuildAttempt ba = BuildAttempt.findById(buildAttemptDTO.id());
        String url = ba.dependencyBuild.buildIdentifier.repository.url;
        BuildInfoService.BuildEditInfo info;
        Optional<BuildInfoService.BuildEditInfo> scmInfo = BuildInfoService.getBuildInfo(url);
        if (scmInfo.isPresent()) {
            info = scmInfo.get();
        } else {
            info = new BuildInfoService.BuildEditInfo(new BuildRecipeInfo(), url, false);
        }
        if (info.buildInfo().getAllowedDifferences() == null) {
            info.buildInfo().setAllowedDifferences(new ArrayList<>());
        }
        for (var i : buildAttemptDTO.upstreamDifferences().entrySet()) {
            for (var dif : i.getValue()) {
                var pat = failureToPattern(dif);
                if (!info.buildInfo().getAllowedDifferences().contains(pat)) {
                    info.buildInfo().getAllowedDifferences().add(pat);
                }
            }
        }
        var prUrul = BuildInfoService.writeBuildInfo(info);
        EditResult ret = new EditResult();
        ret.prUrl = prUrul;
        return ret;
    }

    static String failureToPattern(String failure) {
        Pattern p = Pattern.compile("([+-^]:.+-)(.*)(.jar:.*)");
        Matcher matcher = p.matcher(failure);
        if (matcher.matches()) {
            return matcher.group(1) + ".*?" + matcher.group(3);
        } else {
            return failure;
        }
    }

    public static class EditResult {
        @Schema(required = true)
        public String prUrl;
    }
}
