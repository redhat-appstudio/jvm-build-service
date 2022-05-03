package com.redhat.hacbs.resources.model.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("hacbs.redhat.com")
@Version("v1alpha1")
@JsonInclude(Include.NON_NULL)
public class Component extends CustomResource<ComponentSpec, Status> implements Namespaced {
    @Override
    protected ComponentSpec initSpec() {
        return new ComponentSpec();
    }

    @Override
    protected Status initStatus() {
        return new Status();
    }
}
