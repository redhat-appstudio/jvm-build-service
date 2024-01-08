package com.redhat.hacbs.management.watcher;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.management.model.User;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;

/**
 * class that sets up an initial user if none exists. It creates a secret in the cluster with
 * the details that can be extracted to get the initial login details.
 */
@Startup
public class InitialUserSetup {

    public static final String JBS_USER_SECRET = "jbs-user-secret";
    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "kube.disabled", defaultValue = "false")
    boolean disabled;

    @PostConstruct
    public void setup() {
        if ((LaunchMode.current() == LaunchMode.TEST
                && !Objects.equals(System.getProperty(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY), "test")) || disabled) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate admin user setup");
            return;
        }

        Secret secret = kubernetesClient.resources(Secret.class).withName(JBS_USER_SECRET).get();
        if (secret == null) {
            var sr = new SecureRandom();
            byte[] data = new byte[21];
            sr.nextBytes(data);
            var pw = Base64.getEncoder().encodeToString(data);
            secret = new Secret();
            secret.setMetadata(new ObjectMeta());
            secret.getMetadata().setName(JBS_USER_SECRET);
            secret.setData(Map.of("username", Base64.getEncoder().encodeToString("admin".getBytes(StandardCharsets.UTF_8)),
                    "password", Base64.getEncoder().encodeToString(pw.getBytes(StandardCharsets.UTF_8))));
            kubernetesClient.resource(secret).create();
        }
        var userName = new String(Base64.getDecoder().decode(secret.getData().get("username")), StandardCharsets.UTF_8);
        var password = new String(Base64.getDecoder().decode(secret.getData().get("password")), StandardCharsets.UTF_8);
        User user = User.find("username", userName).firstResult();
        if (user == null) {
            QuarkusTransaction.requiringNew().run(new Runnable() {
                @Override
                public void run() {
                    User user = new User();
                    user.username = userName;
                    user.pass = BcryptUtil.bcryptHash(password);
                    user.persistAndFlush();
                }
            });
        }
    }
}
