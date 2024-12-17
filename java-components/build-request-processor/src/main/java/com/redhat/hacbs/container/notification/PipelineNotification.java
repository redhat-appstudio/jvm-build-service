package com.redhat.hacbs.container.notification;

import org.jboss.pnc.api.dto.Request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Builder;

// TODO: This is a direct copy of the same class in konflux-build-driver. Both need moved to pnc-api to
//      avoid clashes and duplication. For instance, we can't depend upon konflux-build-driver as that
//      then leads to oidc client issues.
@Builder(builderClassName = "Builder")
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineNotification(
        String status,
        String buildId,
        Request completionCallback) {

}
