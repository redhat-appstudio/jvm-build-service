package com.redhat.hacbs.artifactcache.resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.http.client.utils.DateUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;

import com.redhat.hacbs.artifactcache.services.ArtifactResult;
import com.redhat.hacbs.artifactcache.services.CacheFacade;
import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ExecutorRecorder;
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
        var result = cache.getMetadataFiles(buildPolicy, group, "maven-metadata.xml" + hash);
        if (!result.isEmpty()) {
            boolean sha = hash.equals(".sha1");
            if ((commitTime > 0 || result.size() > 1) && (hash.equals("") || sha)) {
                if (sha) {
                    return filterNewerVersions(buildPolicy,
                            cache.getMetadataFiles(buildPolicy, group, "maven-metadata.xml"),
                            new Date(commitTime), group, sha);
                } else {
                    return filterNewerVersions(buildPolicy, result, new Date(commitTime), group, sha);
                }
            }
            //just return the first one, and close the others
            ArtifactResult first = result.get(0);
            for (var i = 1; i < result.size(); ++i) {
                try {
                    result.get(i).close();
                } catch (Throwable t) {
                    Log.error("Failed to close resource", t);
                }
            }
            return first.getData();
        }
        Log.infof("Failed retrieving file %s/%s", group, "maven-metadata.xml");
        throw new NotFoundException();
    }

    private InputStream filterNewerVersions(String buildPolicy, List<ArtifactResult> data, Date commitTime, String group,
            boolean sha1)
            throws Exception {
        long start = System.currentTimeMillis();
        try {
            //group is not really a group
            //depending on if there are plugins or versions
            //we only care about versions, so we assume the last segment
            //of the group is the artifact id
            int lastIndex = group.lastIndexOf('/');
            String artifactId = group.substring(lastIndex + 1);
            String groupId = group.substring(0, lastIndex);
            Metadata outputModel = null;
            boolean firstFile = true;
            //we need to merge additional versions into a single file
            //we assume the first one is the 'most correct' in terms of the 'release' and 'latest' fields

            for (var i : data) {
                try (var in = i.getData()) {
                    MetadataXpp3Reader reader = new MetadataXpp3Reader();
                    var model = reader.read(in);
                    List<String> versions;
                    if (firstFile) {
                        outputModel = model.clone();
                        if (outputModel.getVersioning() == null) {
                            outputModel.setVersioning(new Versioning());
                        }
                        outputModel.getVersioning().setVersions(new ArrayList<>());
                    }
                    if (model.getVersioning() != null) {
                        String release = null;

                        //we need to get the last modified time of all of these versions
                        //we do it async because otherwise for some things it can be really slow
                        Map<String, Future<String>> versionDates = new HashMap<>();

                        if (commitTime.getTime() > 0) {
                            for (String version : model.getVersioning().getVersions()) {
                                versionDates.put(version,
                                        ((ExecutorService) ExecutorRecorder.getCurrent()).submit(new Callable<String>() {
                                            @Override
                                            public String call() throws Exception {
                                                var result = cache.getArtifactFile(buildPolicy, groupId, artifactId, version,
                                                        artifactId + "-" + version + ".pom", false);
                                                if (result.isPresent()) {
                                                    var lastModified = result.get().getMetadata().get("last-modified");
                                                    result.get().close();
                                                    return lastModified;
                                                }
                                                return null;
                                            }
                                        }));
                            }
                        }
                        for (String version : model.getVersioning().getVersions()) {
                            if (commitTime.getTime() > 0) {
                                var result = versionDates.get(version);
                                if (result.get() != null) {
                                    var lastModified = result.get();
                                    if (lastModified != null) {
                                        var date = DateUtils.parseDate(lastModified);
                                        if (date != null && date.after(commitTime)) {
                                            //remove versions released after this artifact
                                            Log.infof("Removing version %s from %s/maven-metadata.xml", version, group);
                                        } else {
                                            //TODO: be smarter about how this release version is selected
                                            release = version;
                                            outputModel.getVersioning().getVersions().add(version);
                                        }
                                    } else {
                                        outputModel.getVersioning().getVersions().add(version);
                                    }
                                }
                            } else {
                                outputModel.getVersioning().getVersions().add(version);
                            }
                        }
                        if (firstFile) {
                            outputModel.getVersioning().setRelease(release);
                            outputModel.getVersioning().setLatest(release);
                            outputModel.getVersioning().setLastUpdatedTimestamp(commitTime);
                        }
                    }
                }
                firstFile = false;
            }
            MetadataXpp3Writer writer = new MetadataXpp3Writer();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.write(out, outputModel);
            Log.infof("Generated %s/maven-metadata.xml in %sms", group, System.currentTimeMillis() - start);
            if (sha1) {
                return new ByteArrayInputStream(HashUtil.sha1(out.toByteArray()).getBytes(StandardCharsets.UTF_8));
            } else {
                return new ByteArrayInputStream(out.toByteArray());
            }
        } finally {
            for (var i : data) {
                try {
                    i.close();
                } catch (Throwable t) {
                    Log.error("Failed to close resource", t);
                }
            }
        }
    }

}
