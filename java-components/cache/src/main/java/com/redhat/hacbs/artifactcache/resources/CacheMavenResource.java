package com.redhat.hacbs.artifactcache.resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Iterator;

import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.http.client.utils.DateUtils;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;

import com.redhat.hacbs.artifactcache.services.CacheFacade;
import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;

@Path("/v1/cache/")
@Blocking
public class CacheMavenResource {

    final CacheFacade cache;

    public CacheMavenResource(CacheFacade cache) {
        this.cache = cache;
    }

    @GET
    @Path("{build-policy}/{commit-time}/{group:.*?}/{artifact}/{version}/{target}")
    public Response get(@PathParam("build-policy") String buildPolicy,
            @PathParam("group") String group,
            @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target) throws Exception {
        Log.debugf("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
        var result = cache.getArtifactFile(buildPolicy, group, artifact, version, target, true);
        if (result.isPresent()) {
            var builder = Response.ok(result.get().getData());
            if (result.get().getMetadata().containsKey("maven-repo")) {
                builder.header("X-maven-repo", result.get().getMetadata().get("maven-repo"))
                        .build();
            }
            if (result.get().getSize() > 0) {
                builder.header(HttpHeaders.CONTENT_LENGTH, result.get().getSize());
            }
            return builder.build();
        }
        Log.infof("Failed to get artifact %s/%s/%s/%s", group, artifact, version, target);
        throw new NotFoundException();
    }

    @GET
    @Path("{build-policy}/{commit-time}/{group:.*?}/maven-metadata.xml{hash:.*?}")
    public InputStream get(@PathParam("build-policy") String buildPolicy,
            @PathParam("commit-time") long commitTime,
            @PathParam("group") String group,
            @PathParam("hash") String hash) throws Exception {
        Log.debugf("Retrieving file %s/%s", group, "maven-metadata.xml");
        var result = cache.getMetadataFile(buildPolicy, group, "maven-metadata.xml" + hash);
        if (result.isPresent()) {
            boolean sha = hash.equals(".sha1");
            if (commitTime > 0 && (hash.equals("") || sha)) {
                if (sha) {
                    return filterNewerVersions(buildPolicy,
                            cache.getMetadataFile(buildPolicy, group, "maven-metadata.xml").get().getData(),
                            new Date(commitTime), group, sha);
                } else {
                    return filterNewerVersions(buildPolicy, result.get().getData(), new Date(commitTime), group, sha);
                }
            }
            return result.get().getData();
        }
        Log.infof("Failed retrieving file %s/%s", group, "maven-metadata.xml");
        throw new NotFoundException();
    }

    private InputStream filterNewerVersions(String buildPolicy, InputStream data, Date commitTime, String group, boolean sha1)
            throws Exception {
        //group is not really a group
        //depending on if there are plugins or versions
        //we only care about versions, so we assume the last segment
        //of the group is the artifact id
        int lastIndex = group.lastIndexOf('/');
        String artifactId = group.substring(lastIndex + 1);
        String groupId = group.substring(0, lastIndex);
        try (var in = data) {
            MetadataXpp3Reader reader = new MetadataXpp3Reader();
            var model = reader.read(data);
            if (model.getVersioning() != null) {
                String release = null;
                for (Iterator<String> iterator = model.getVersioning().getVersions().iterator(); iterator.hasNext();) {
                    String version = iterator.next();
                    var result = cache.getArtifactFile(buildPolicy, groupId, artifactId, version,
                            artifactId + "-" + version + ".pom", false);
                    if (result.isPresent()) {
                        var lastModified = result.get().getMetadata().get("last-modified");
                        if (lastModified != null) {
                            var date = DateUtils.parseDate(lastModified);
                            if (date.after(commitTime)) {
                                //remove versions released after this artifact
                                Log.infof("Removing version %s from %s/maven-metadata.xml", version, group);
                                iterator.remove();
                            } else {
                                //TODO: be smarter about how this release version is selected
                                release = version;
                            }
                        }
                    } else {
                        iterator.remove();
                    }

                }
                model.getVersioning().setRelease(release);
                model.getVersioning().setLatest(release);
                model.getVersioning().setLastUpdatedTimestamp(commitTime);
            }
            MetadataXpp3Writer writer = new MetadataXpp3Writer();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.write(out, model);
            if (sha1) {
                return new ByteArrayInputStream(HashUtil.sha1(out.toByteArray()).getBytes(StandardCharsets.UTF_8));
            } else {
                return new ByteArrayInputStream(out.toByteArray());
            }
        }

    }

}
