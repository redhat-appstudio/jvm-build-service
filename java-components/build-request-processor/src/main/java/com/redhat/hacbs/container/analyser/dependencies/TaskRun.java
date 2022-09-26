package com.redhat.hacbs.container.analyser.dependencies;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("tekton.dev")
@Version("v1beta1")
@JsonInclude(Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskRun extends CustomResource<TaskRunSpec, TaskRunStatus>
        implements Namespaced {

    @Override
    protected TaskRunSpec initSpec() {
        return new TaskRunSpec();
    }

    @Override
    protected TaskRunStatus initStatus() {
        return new TaskRunStatus();
    }
}
