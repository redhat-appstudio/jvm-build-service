package com.redhat.hacbs.management.resources;

import java.util.Optional;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.common.tools.repo.BuildInfoService;
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

    public static class EditResult {
        @Schema(required = true)
        public String prUrl;
    }
}
