package com.redhat.hacbs.artifactcache.artifactwatch;

import java.util.Date;
import java.util.function.UnaryOperator;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.http.client.utils.DateUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.artifactcache.services.StorageManager;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;

@ApplicationScoped
@Startup
public class CacheControl {

    @Inject
    StorageManager storageManager;

    @Inject
    KubernetesClient client;

    SharedIndexInformer<GenericKubernetesResource> informer;

    @ConfigProperty(name = "kube.disabled", defaultValue = "false")
    boolean disabled;

    @PostConstruct
    void setup() {
        if (LaunchMode.current() == LaunchMode.TEST || disabled) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate CacheControl");
            return;
        }

        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> jbsConfig = client
                .genericKubernetesResources(ModelConstants.GROUP + "/" + ModelConstants.VERSION, "JBSConfig");

        informer = jbsConfig.inform();
        informer.addEventHandler(new ResourceEventHandler<GenericKubernetesResource>() {
            @Override
            public void onAdd(GenericKubernetesResource obj) {
                handleObject(obj);
            }

            @Override
            public void onUpdate(GenericKubernetesResource oldObj, GenericKubernetesResource newObj) {
                handleObject(newObj);
            }

            private void handleObject(GenericKubernetesResource newObj) {
                try {
                    var anns = newObj.getMetadata().getAnnotations();
                    if (anns != null) {
                        if (anns.containsKey(ModelConstants.CLEAR_CACHE)) {
                            storageManager.clear();
                            jbsConfig.withName(newObj.getMetadata().getName())
                                    .edit(new UnaryOperator<GenericKubernetesResource>() {
                                        @Override
                                        public GenericKubernetesResource apply(
                                                GenericKubernetesResource genericKubernetesResource) {
                                            genericKubernetesResource.getMetadata()
                                                    .getAnnotations()
                                                    .remove(ModelConstants.CLEAR_CACHE);
                                            genericKubernetesResource.getMetadata()
                                                    .getAnnotations()
                                                    .put(ModelConstants.LAST_CLEAR_CACHE,
                                                            DateUtils.formatDate(new Date()));
                                            return genericKubernetesResource;
                                        }
                                    });
                        }
                    }

                } catch (Throwable t) {
                    t.printStackTrace();
                }

            }

            @Override
            public void onDelete(GenericKubernetesResource obj, boolean deletedFinalStateUnknown) {

            }
        });
    }
}
