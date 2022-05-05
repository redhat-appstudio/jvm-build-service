package com.redhat.hacbs.operator;

import javax.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

@ControllerConfiguration
public class DependencyBuildReconciler implements Reconciler<DependencyBuild> {
    @Inject
    KubernetesClient client;

    @Override
    public UpdateControl<DependencyBuild> reconcile(DependencyBuild component, Context context) {
        // TODO Auto-generated method stub
        return null;
    }
}
