package com.redhat.hacbs.management.resources;

import java.util.Map;
import java.util.Optional;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.common.tools.repo.ScmInfoService;
import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.recipes.scm.ScmInfo;

@Path("/artifacts/edit")
public class ArtifactEditResource {

    @GET
    public ScmInfoService.ScmEditInfo get(@QueryParam("gav") String gav) {
        Optional<ScmInfoService.ScmEditInfo> scmInfo = ScmInfoService.getScmInfo(gav);
        if (scmInfo.isPresent()) {
            return scmInfo.get();
        }
        return new ScmInfoService.ScmEditInfo(new ScmInfo(), false, false, gav);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public EditResult modify(ScmInfoService.ScmEditInfo command) {
        var prUrul = ScmInfoService.writeScmInfo(command);
        EditResult ret = new EditResult();
        ret.prUrl = prUrul;
        return ret;
    }

    @POST
    @Transactional
    @Path("rebuild")
    @Consumes(MediaType.TEXT_PLAIN)
    public void modify(String gav) {
        BuildQueue.rebuild(gav, true, Map.of());
    }

    public static class EditResult {
        @Schema(required = true)
        public String prUrl;
    }
}
