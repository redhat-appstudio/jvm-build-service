package com.redhat.hacbs.artifactcache.resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
import org.apache.maven.artifact.versioning.ComparableVersion;

import com.redhat.hacbs.artifactcache.services.ArtifactResult;
import com.redhat.hacbs.artifactcache.services.BuildPolicy;
import com.redhat.hacbs.artifactcache.services.CacheFacade;
import com.redhat.hacbs.artifactcache.services.RemoteRepositoryManager;
import com.redhat.hacbs.artifactcache.services.RepositoryCache;
import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;

@Path("/v2/cache/")
@Blocking
public class V2CacheMavenResource {

    final CacheFacade cache;
    final RemoteRepositoryManager remoteRepositoryManager;

    public V2CacheMavenResource(CacheFacade cache, RemoteRepositoryManager remoteRepositoryManager) {
        this.cache = cache;
        this.remoteRepositoryManager = remoteRepositoryManager;
    }

    @GET
    @Path("rebuild{stores:(-[\\w-,]+)?}/{commit-time}/{group:.*?}/{artifact}/{version}/{target}")
    public Response getRebuild(@PathParam("stores") String stores,
            @PathParam("group") String group,
            @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target) throws Exception {

        CacheFacade facade = rebuildCache(stores);
        Log.debugf("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
        var result = facade.getArtifactFile("", group, artifact, version, target, true);
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

    private CacheFacade rebuildCache(String stores) throws Exception {
        List<RepositoryCache> caches = new ArrayList<>();
        caches.addAll(remoteRepositoryManager.getRemoteRepositories("rebuilt"));
        if (stores.length() > 1) {
            stores = stores.substring(1);
            for (var i : stores.split(",")) {
                var store = remoteRepositoryManager.getRemoteRepositories(RemoteRepositoryManager.SYSTEM + i);
                if (store != null) {
                    caches.addAll(store);
                } else {
                    Log.infof("Could not find system store %s", i);
                }
            }
        } else {
            //TODO: once we have added repository info the the main recipe database we should change this to 'default'
            caches.addAll(remoteRepositoryManager.getRemoteRepositories(RemoteRepositoryManager.SYSTEM + "all"));
        }
        BuildPolicy bp = new BuildPolicy(caches);
        return new CacheFacade(Map.of("", bp));
    }

    @GET
    @Path("rebuild{stores:(-[\\w-,]+)?}/{commit-time}/{group:.*?}/maven-metadata.xml{hash:.*?}")
    public InputStream getRebuild(@PathParam("stores") String stores,
            @PathParam("commit-time") long commitTime,
            @PathParam("group") String group,
            @PathParam("hash") String hash) throws Exception {
        Log.debugf("Retrieving file %s/%s", group, "maven-metadata.xml");
        CacheFacade cache = rebuildCache(stores);
        var result = cache.getMetadataFiles("", group, "maven-metadata.xml" + hash);
        if (!result.isEmpty()) {
            boolean sha = hash.equals(".sha1");
            if ((commitTime > 0 || result.size() > 1) && (hash.equals("") || sha)) {
                if (sha) {
                    return filterNewerVersions(cache, "",
                            cache.getMetadataFiles("", group, "maven-metadata.xml"),
                            new Date(commitTime), group, sha);
                } else {
                    return filterNewerVersions(cache, "", result, new Date(commitTime), group, sha);
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

    @GET
    @Path("user/{build-policy}/{group:.*?}/{artifact}/{version}/{target}")
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
    @Path("user/{build-policy}/{group:.*?}/maven-metadata.xml{hash:.*?}")
    public InputStream get(@PathParam("build-policy") String buildPolicy,
            @PathParam("group") String group,
            @PathParam("hash") String hash) throws Exception {
        Log.debugf("Retrieving file %s/%s", group, "maven-metadata.xml");
        var result = cache.getMetadataFiles(buildPolicy, group, "maven-metadata.xml" + hash);
        if (!result.isEmpty()) {
            boolean sha = hash.equals(".sha1");
            if ((result.size() > 1) && (hash.equals("") || sha)) {
                if (sha) {
                    return filterNewerVersions(cache, buildPolicy,
                            cache.getMetadataFiles(buildPolicy, group, "maven-metadata.xml"),
                            new Date(0), group, sha);
                } else {
                    return filterNewerVersions(cache, buildPolicy, result, new Date(0), group, sha);
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

    private InputStream filterNewerVersions(CacheFacade cache, String buildPolicy, List<ArtifactResult> data, Date commitTime,
            String group,
            boolean sha1)
            throws Exception {
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
            Set<String> seenVersions = new TreeSet<>(new Comparator<String>() {
                @Override
                public int compare(String o1, String o2) {
                    return new ComparableVersion(o2).compareTo(new ComparableVersion(o1));
                }
            });
            //we need to merge additional versions into a single file
            for (var i : data) {
                try (var in = i.getData()) {
                    MetadataXpp3Reader reader = new MetadataXpp3Reader();
                    var model = reader.read(in);
                    if (firstFile) {
                        outputModel = model.clone();
                        if (outputModel.getVersioning() == null) {
                            outputModel.setVersioning(new Versioning());
                        }
                        outputModel.getVersioning().setVersions(new ArrayList<>());
                    }
                    if (model.getVersioning() != null) {
                        seenVersions.addAll(model.getVersioning().getVersions());
                    }
                }
                firstFile = false;
            }
            //iterate most recent to oldest
            //once we have started including older versions then we can stop checking
            //technically an older point release may still end up being present that was
            //not there at the commit time, but in practice this should not be an issue
            boolean addAll = false;
            for (var version : seenVersions) {
                if (version.contains("SNAPSHOT")) {
                    continue;
                }
                if (addAll) {
                    outputModel.getVersioning().getVersions().add(version);
                } else {
                    if (commitTime.getTime() > 0) {
                        var result = cache.getArtifactMetadata(buildPolicy, groupId, artifactId, version,
                                artifactId + "-" + version + ".pom", false);
                        if (result.isPresent()) {
                            var lastModified = result.get().get("last-modified");
                            if (lastModified != null) {
                                var date = DateUtils.parseDate(lastModified);
                                if (date != null && date.after(commitTime)) {
                                    //remove versions released after this artifact
                                    Log.infof("Removing version %s from %s/maven-metadata.xml", version, group);
                                    continue;
                                }
                            }
                        } else {
                            //not found, don't add it
                            continue;
                        }
                    }
                    //the default behaviour is to add the version, set it to latest and move to add all mode
                    //if the artifact is not found or too new then it this is skipped by the continue statements
                    //above
                    outputModel.getVersioning().getVersions().add(version);
                    outputModel.getVersioning().setRelease(version);
                    outputModel.getVersioning().setLatest(version);
                    outputModel.getVersioning().setLastUpdatedTimestamp(commitTime);
                    addAll = true;
                }
            }
            MetadataXpp3Writer writer = new MetadataXpp3Writer();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.write(out, outputModel);
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
