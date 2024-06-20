package com.redhat.hacbs.container.analyser.location;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.microprofile.rest.client.RestClientBuilder;

import com.redhat.hacbs.recipes.scm.ScmLocator;
import com.redhat.hacbs.resources.model.maven.GAV;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "lookup-scm")
// Deprecated as scm discovery pipeline has been removed as the request controller
// requests this information directly from the cache.
@Deprecated
public class LookupScmLocationCommand implements Runnable {

    public static final String CACHE_PATH = "/v2/cache/user/default";
    @CommandLine.Option(names = "--gav", required = true)
    String gav;

    //these are paths to files to write the results to for tekton
    @CommandLine.Option(names = "--scm-url")
    Path scmUrl;

    @CommandLine.Option(names = "--scm-type")
    Path scmType;
    @CommandLine.Option(names = "--scm-tag")
    Path scmTag;
    @CommandLine.Option(names = "--scm-hash")
    Path scmHash;

    @CommandLine.Option(names = "--private")
    Path privateRepo;

    @CommandLine.Option(names = "--message")
    Path message;

    @CommandLine.Option(names = "--context")
    Path context;

    @CommandLine.Option(names = "--cache-url", required = true)
    String cacheUrl;

    @Override
    public void run() {
        try {
            GAV toBuild = GAV.parse(gav);
            Log.infof("Looking up %s", gav);
            var scmLocator = getScmLocator();
            var tagInfo = scmLocator.resolveTagInfo(toBuild);
            if (tagInfo != null) {
                Log.infof("Found tag %s", tagInfo.getTag());
                if (scmTag != null) {
                    Files.writeString(scmTag, tagInfo.getTag());
                }
                if (scmHash != null) {
                    String gitHash = tagInfo.getHash();
                    if (gitHash == null) {
                        //should only happen if the tag is already a ref
                        gitHash = tagInfo.getTag();
                    }
                    Files.writeString(scmHash, gitHash);
                }
                Log.infof("SCM URL: %s", tagInfo.getRepoInfo().getUri());
                if (scmUrl != null) {
                    Files.writeString(scmUrl, tagInfo.getRepoInfo().getUri());
                }
                if (scmType != null) {
                    Files.writeString(scmType, "git");
                }
                Log.infof("Path: %s", tagInfo.getRepoInfo().getPath());
                if (context != null) {
                    if (tagInfo.getRepoInfo().getPath() != null) {
                        Files.writeString(context, tagInfo.getRepoInfo().getPath());
                    } else {
                        Files.createFile(context);
                    }
                }
                if (privateRepo != null) {
                    Files.writeString(privateRepo, Boolean.toString(tagInfo.getRepoInfo().isPrivateRepo()));
                }
                if (message != null) {
                    Files.createFile(message);
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
            if (message != null) {
                try {
                    Files.writeString(message, "Failed to determine tag for " + gav + ". Failure reason: " + e.getMessage());
                } catch (IOException ex) {
                    Log.errorf(e, "Failed to write result");
                }
            }
        }
    }

    private ScmLocator getScmLocator() {
        try {
            return RestClientBuilder.newBuilder().baseUri(new URI(cacheUrl)).build(CacheScmLocator.class);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
