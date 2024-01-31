package com.redhat.hacbs.management.resources;

import java.util.Map;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.common.tools.recipes.ModifyScmRepoCommand;
import com.redhat.hacbs.management.model.BuildQueue;

@Path("/artifacts/edit")
public class ArtifactEditResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public EditResult modify(ModifyScmRepoCommand command) {
        var prUrul = command.run();
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
