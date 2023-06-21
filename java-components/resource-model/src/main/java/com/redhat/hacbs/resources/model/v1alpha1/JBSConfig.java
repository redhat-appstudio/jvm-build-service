package com.redhat.hacbs.resources.model.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group(ModelConstants.GROUP)
@Version(ModelConstants.VERSION)
@JsonInclude(Include.NON_NULL)
public class JBSConfig extends CustomResource<JBSConfigSpec, JBSConfigStatus>
        implements Namespaced {
    public static final String NAME = "jvm-build-config";

    @Override
    protected JBSConfigSpec initSpec() {
        return new JBSConfigSpec();
    }

    @Override
    protected JBSConfigStatus initStatus() {
        return new JBSConfigStatus();
    }
}
