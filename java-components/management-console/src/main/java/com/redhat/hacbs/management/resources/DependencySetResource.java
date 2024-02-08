package com.redhat.hacbs.management.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import com.redhat.hacbs.management.dto.DependencySetDTO;
import com.redhat.hacbs.management.dto.IdentifiedDependencyDTO;
import com.redhat.hacbs.management.model.DependencySet;

@Path("/dependency-set")
public class DependencySetResource {

    @GET
    @Path("{id}")
    public DependencySetDTO getDependencySet(@PathParam("id") long id) {
        DependencySet deps = DependencySet.findById(id);
        if (deps == null) {
            throw new NotFoundException();
        }
        return new DependencySetDTO(id, deps.identifier, IdentifiedDependencyDTO.fromDependencySet(deps));
    }

}
