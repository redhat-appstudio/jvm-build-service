package com.redhat.hacbs.artifactcache.artifactwatch;

import java.util.Date;
import java.util.function.UnaryOperator;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.http.client.utils.DateUtils;

import com.redhat.hacbs.artifactcache.services.StorageManager;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
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

    @PostConstruct
    void setup() {
        if (LaunchMode.current() == LaunchMode.TEST) {
            //don't start in tests, as kube might not be present
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
                                            genericKubernetesResource.getMetadata().getAnnotations()
                                                    .remove(ModelConstants.CLEAR_CACHE);
                                            genericKubernetesResource.getMetadata().getAnnotations()
                                                    .put(ModelConstants.LAST_CLEAR_CACHE, DateUtils.formatDate(new Date()));
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
