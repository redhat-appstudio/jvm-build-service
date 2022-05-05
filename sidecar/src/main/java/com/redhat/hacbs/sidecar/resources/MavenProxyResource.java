package com.redhat.hacbs.sidecar.resources;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;
import com.redhat.hacbs.sidecar.services.RemoteClient;

import io.quarkus.logging.Log;
import io.quarkus.runtime.util.HashUtil;
import io.smallrye.common.annotation.Blocking;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Path("/maven2")
@Blocking
@Singleton
public class MavenProxyResource {

    final RemoteClient remoteClient;
    final String buildPolicy;
    final S3Client client;
    final String deploymentBucket;
    final String deploymentPrefix;

    final boolean addTrackingDataToArtifacts;

    final Map<String, String> computedChecksums = new ConcurrentHashMap<>();

    public MavenProxyResource(@RestClient RemoteClient remoteClient,
            @ConfigProperty(name = "build-policy") String buildPolicy, S3Client client,
            @ConfigProperty(name = "deployment-bucket") String deploymentBucket,
            @ConfigProperty(name = "deployment-prefix") String deploymentPrefix,
            @ConfigProperty(name = "add-tracking-data-to-artifacts", defaultValue = "true") boolean addTrackingDataToArtifacts) {
        this.remoteClient = remoteClient;
        this.buildPolicy = buildPolicy;
        this.client = client;
        this.deploymentBucket = deploymentBucket;
        this.deploymentPrefix = deploymentPrefix;
        this.addTrackingDataToArtifacts = addTrackingDataToArtifacts;
        Log.infof("Constructing resource manager with build policy %s", buildPolicy);
    }

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    public byte[] get(@PathParam("group") String group, @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target) throws Exception {
        Log.debugf("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
        if (target.endsWith(".sha1")) {
            String key = group + "/" + artifact + "/" + version + "/" + target;
            var modified = computedChecksums.get(key);
            if (modified != null) {
                return modified.getBytes(StandardCharsets.UTF_8);
            }
        }
        try {
            var results = remoteClient.get(buildPolicy, group, artifact, version, target);
            var mavenRepoSource = results.getHeaderString("X-maven-repo");
            if (addTrackingDataToArtifacts && target.endsWith(".jar") && mavenRepoSource != null) {
                var modified = ClassFileTracker.addTrackingDataToJar(results.readEntity(byte[].class),
                        new TrackingData(group.replace("/", ".") + ":" + artifact + ":" + version, mavenRepoSource));
                String key = group + "/" + artifact + "/" + version + "/" + target + ".sha1";
                computedChecksums.put(key, HashUtil.sha1(modified));
                return modified;
            } else {
                return results.readEntity(byte[].class);
            }
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) {
                throw new NotFoundException();
            }
            Log.errorf(e, "Failed to load %s", target);
            throw e;
        } catch (Exception e) {
            Log.errorf(e, "Failed to load %s", target);
            throw e;
        }
    }

    @GET
    @Path("{group:.*?}/maven-metadata.xml{hash:.*?}")
    public byte[] get(@PathParam("group") String group, @PathParam("hash") String hash) throws Exception {
        Log.debugf("Retrieving file %s/maven-metadata.xml%s", group, hash);
        try {
            Response response = remoteClient.get(buildPolicy, group, "maven-metadata.xml" + hash);
            return response.readEntity(byte[].class);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) {
                throw new NotFoundException();
            }
            throw e;
        }
    }

    //TODO: deployment is really hacked up at the moment, this needs to be modified to be an atomic operation
    //this is ok for the PoC phase, but needs to be fixed
    //We will likely either want to use the nexus plugin and emulate the nexus API, or write out own plugin
    //alternatively we could deploy to a file system location and have a task that does the deployment from there
    @PUT
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    public void deploy(@PathParam("group") String group, @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target, byte[] data) throws Exception {
        Log.errorf("DEPLOYING artifact %s/%s/%s/%s", group, artifact, version, target);

        String fullTarget = deploymentPrefix + "/" + group + "/" + artifact + "/" + version + "/" + target;
        client.putObject(PutObjectRequest.builder().bucket(deploymentBucket).key(fullTarget).build(),
                RequestBody.fromBytes(data));
    }

    @PUT
    @Path("{group:.*?}/maven-metadata.xml{hash:.*?}")
    public void get(@PathParam("group") String group, @PathParam("hash") String hash, byte[] data) throws Exception {
        String fullTarget = deploymentPrefix + "/" + group + "/maven-metadata.xml" + hash;
        client.putObject(PutObjectRequest.builder().bucket(deploymentBucket).key(fullTarget).build(),
                RequestBody.fromBytes(data));
    }
}
