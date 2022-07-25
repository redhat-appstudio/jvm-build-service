package com.redhat.hacbs.sidecar.resources;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.NoCloseInputStream;
import com.redhat.hacbs.sidecar.resources.deploy.Deployer;
import com.redhat.hacbs.sidecar.resources.deploy.DeployerUtil;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.common.annotation.Blocking;

@Path("/deploy")
@Blocking
@Singleton
@Startup
public class DeployResource {

    final Set<String> contaminates = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Throwable deployFailure;

    final Deployer deployer;
    final BeanManager beanManager;

    /**
     * We can ignore some artifacts that are problematic as part of deployment.
     * <p>
     * Generally this is for things like CLI's, that shade in lots of dependencies,
     * but are not generally useful for downstream builds.
     */
    Set<String> doNotDeploy;
    Set<String> allowedSources;

    public DeployResource(BeanManager beanManager,
            @ConfigProperty(name = "deployer", defaultValue = "S3Deployer") String deployer,
            @ConfigProperty(name = "ignored-artifacts", defaultValue = "") Optional<Set<String>> doNotDeploy,
            @ConfigProperty(name = "allowed-sources", defaultValue = "") Optional<Set<String>> allowedSources) {
        this.beanManager = beanManager;
        this.deployer = getDeployer(deployer);
        this.doNotDeploy = doNotDeploy.orElse(Set.of());
        this.allowedSources = allowedSources.orElse(Set.of());
        Log.debugf("Using %s deployer", deployer);
        Log.debugf("Ignored Artifacts: %s", doNotDeploy);
    }

    @GET
    @Path("/result")
    public Response contaminates() {
        Log.debugf("Getting contaminates for build: " + contaminates);
        if (deployFailure != null) {
            throw new RuntimeException(deployFailure);
        }
        if (contaminates.isEmpty()) {
            return Response.noContent().build();
        } else {
            StringBuilder response = new StringBuilder();
            boolean first = true;
            for (var i : contaminates) {
                //it is recommended that tekton results not be larger than 1k
                //if this happens we just return some of the contaminates
                //it does not matter that much, as if all the reported ones are
                //fixed then we will re-discover the rest next build
                if (response.length() > 400) {
                    Log.error("Contaminates truncated");
                    break;
                }
                if (first) {
                    first = false;
                } else {
                    response.append(",");
                }
                response.append(i);
            }
            Log.debugf("Contaminates returned: " + response);
            return Response.ok(response).build();
        }
    }

    @POST
    public void deployArchive(InputStream data) throws Exception {
        java.nio.file.Path temp = Files.createTempFile("deployment", ".tar.gz");
        try (var out = Files.newOutputStream(temp)) {
            byte[] buf = new byte[1024];
            int r;
            while ((r = data.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }

        Set<String> contaminants = new HashSet<>();
        Map<String, Set<String>> contaminatedPaths = new HashMap<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(
                new GzipCompressorInputStream(Files.newInputStream(temp)))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                if (e.getName().endsWith(".jar")) {
                    if (!DeployerUtil.shouldIgnore(doNotDeploy, e.getName())) {
                        Log.debugf("Checking %s for contaminants", e.getName());
                        var info = ClassFileTracker.readTrackingDataFromJar(new NoCloseInputStream(in), e.getName());
                        if (info != null) {
                            for (var i : info) {
                                if (!allowedSources.contains(i.source)) {
                                    //Set<String> result = new HashSet<>(info.stream().map(a -> a.gav).toList());
                                    contaminants.add(i.gav);
                                    int index = e.getName().lastIndexOf("/");
                                    if (index != -1) {
                                        contaminatedPaths
                                                .computeIfAbsent(e.getName().substring(0, index), s -> new HashSet<>())
                                                .add(i.gav);
                                    } else {
                                        contaminatedPaths.computeIfAbsent("", s -> new HashSet<>()).add(i.gav);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Log.debugf("Contaminants: %s", contaminatedPaths);
        this.contaminates.addAll(contaminants);

        if (contaminants.isEmpty()) {

            try {
                deployer.deployArchive(temp);
            } catch (Throwable t) {
                Log.error("Deployment failed", t);
                deployFailure = t;
                flushLogs();
                throw t;
            }
        } else {
            Log.error("Not performing deployment due to community dependencies contaminating the result");
        }
    }

    private Deployer getDeployer(String name) {
        Bean<Deployer> bean = (Bean<Deployer>) beanManager.getBeans(name).iterator().next();
        CreationalContext<Deployer> ctx = beanManager.createCreationalContext(bean);
        return (Deployer) beanManager.getReference(bean, Deployer.class, ctx);
    }

    private void flushLogs() {
        System.err.flush();
        System.out.flush();
    }

}
