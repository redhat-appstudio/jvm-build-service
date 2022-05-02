package com.redhat.hacbs.operator;

import javax.inject.Inject;

import com.redhat.hacbs.operator.model.v1alpha1.ArtifactBuildRequest;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

@ControllerConfiguration
public class ArtifactBuildRequestReconciler implements Reconciler<ArtifactBuildRequest> {
    @Inject
    KubernetesClient client;

    @Override
    public UpdateControl<ArtifactBuildRequest> reconcile(ArtifactBuildRequest component, Context context) {
        // TODO Auto-generated method stub
        return null;
    }
}
