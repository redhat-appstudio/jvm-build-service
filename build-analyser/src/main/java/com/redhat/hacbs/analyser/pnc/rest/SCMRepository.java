package com.redhat.hacbs.analyser.pnc.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Configuration of the SCM repository.
 *
 * @author Jakub Bartecek &lt;jbartece@redhat.com&gt;
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SCMRepository implements DTOEntity {

    protected String id;

    /**
     * URL to the internal SCM repository, which is the main repository used for the builds. New commits can be added to
     * this repository, during the pre-build steps of the build process.
     */
    protected String internalUrl;

    /**
     * URL to the upstream SCM repository.
     */
    protected String externalUrl;

    /**
     * Declares whether the pre-build repository synchronization from external repository should happen or not.
     */
    protected Boolean preBuildSyncEnabled;

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getInternalUrl() {
        return internalUrl;
    }

    public void setInternalUrl(String internalUrl) {
        this.internalUrl = internalUrl;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
        this.externalUrl = externalUrl;
    }

    public Boolean getPreBuildSyncEnabled() {
        return preBuildSyncEnabled;
    }

    public void setPreBuildSyncEnabled(Boolean preBuildSyncEnabled) {
        this.preBuildSyncEnabled = preBuildSyncEnabled;
    }
}
