package com.redhat.hacbs.artifactcache.artifactwatch;

import java.util.Date;
import java.util.function.UnaryOperator;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.http.client.utils.DateUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.artifactcache.services.StorageManager;
import com.redhat.hacbs.resources.model.v1alpha1.JBSConfig;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;
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

    SharedIndexInformer<JBSConfig> informer;

    @ConfigProperty(name = "kube.disabled", defaultValue = "false")
    boolean disabled;

    @PostConstruct
    void setup() {
        if (LaunchMode.current() == LaunchMode.TEST || disabled) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate CacheControl");
            return;
        }

        var jbsConfig = client.resources(JBSConfig.class);

        informer = jbsConfig.inform();
        informer.addEventHandler(new ResourceEventHandler<JBSConfig>() {
            @Override
            public void onAdd(JBSConfig obj) {
                handleObject(obj);
            }

            @Override
            public void onUpdate(JBSConfig oldObj, JBSConfig newObj) {
                handleObject(newObj);
            }

            private void handleObject(JBSConfig newObj) {
                Log.infof("reconciling JBSConfig");
                try {
                    var anns = newObj.getMetadata().getAnnotations();
                    if (anns != null) {
                        if (anns.containsKey(ModelConstants.CLEAR_CACHE)) {
                            Log.infof("clearing cache");
                            storageManager.clear();
                            jbsConfig.withName(newObj.getMetadata().getName())
                                    .edit(new UnaryOperator<JBSConfig>() {
                                        @Override
                                        public JBSConfig apply(JBSConfig config) {
                                            config.getMetadata()
                                                    .getAnnotations()
                                                    .remove(ModelConstants.CLEAR_CACHE);
                                            config.getMetadata()
                                                    .getAnnotations()
                                                    .put(ModelConstants.LAST_CLEAR_CACHE,
                                                            DateUtils.formatDate(new Date()));
                                            return config;
                                        }
                                    });
                        }
                    }

                } catch (Throwable t) {
                    t.printStackTrace();
                }

            }

            @Override
            public void onDelete(JBSConfig obj, boolean deletedFinalStateUnknown) {

            }
        });
    }
}
