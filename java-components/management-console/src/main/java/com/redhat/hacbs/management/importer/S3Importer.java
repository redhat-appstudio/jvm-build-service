package com.redhat.hacbs.management.importer;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.quarkus.logging.Log;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;

@Singleton
//@Startup
public class S3Importer {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());
    public static final String ARTIFACTS = "artifacts/";
    public static final String BUILDS = "builds/";

    @Inject
    S3Client s3;

    @Inject
    DependencyBuildImporter dependencyBuildImporter;
    @Inject
    ArtifactBuildImporter artifactBuildImporter;

    @ConfigProperty(name = "bucket.name")
    String bucketName;

    @PostConstruct
    public void doImport() {
        Log.infof("Attempting S3 import");
        importArtifactBuilds();
        importDependencyBuilds();
    }

    private void importDependencyBuilds() {
        ListObjectsRequest listRequest = ListObjectsRequest.builder().prefix(BUILDS).bucket(bucketName).build();
        for (;;) {
            ListObjectsResponse listObjectsResponse = s3.listObjects(listRequest);
            for (var i : listObjectsResponse.contents()) {
                Log.infof("Examining S3 key %s", i.key());
                if (!i.key().contains("/pipelines/")) { //TODO: remove this, it is a  legacy of an old S3 based storage layout
                    Log.infof("Importing DependencyBuild from S3 object %s", i.key());
                    var res = s3.getObjectAsBytes(GetObjectRequest.builder().key(i.key()).bucket(bucketName).build());
                    try {
                        DependencyBuild build = MAPPER.readerFor(DependencyBuild.class).readValue(res.asInputStream());
                        dependencyBuildImporter.doImport(build);
                    } catch (Exception e) {
                        Log.errorf(e, "Failed to import %s", i.key());
                    }
                }
            }
            if (!listObjectsResponse.isTruncated()) {
                return;
            }
            listRequest = ListObjectsRequest.builder().bucket(bucketName).prefix(BUILDS)
                    .marker(listObjectsResponse.contents().get(listObjectsResponse.contents().size() - 1).key())
                    .build();
        }
    }

    private void importArtifactBuilds() {
        ListObjectsRequest listRequest = ListObjectsRequest.builder().prefix(ARTIFACTS).bucket(bucketName).build();
        for (;;) {
            ListObjectsResponse listObjectsResponse = s3.listObjects(listRequest);
            for (var i : listObjectsResponse.contents()) {
                Log.infof("Examining S3 key %s", i.key());
                Log.infof("Importing ArtifactBuild from S3 object %s", i.key());
                var res = s3.getObjectAsBytes(GetObjectRequest.builder().key(i.key()).bucket(bucketName).build());
                try {
                    ArtifactBuild build = MAPPER.readerFor(ArtifactBuild.class).readValue(res.asInputStream());
                    artifactBuildImporter.doImport(build);
                } catch (Exception e) {
                    Log.errorf(e, "Failed to import %s", i.key());
                }
            }
            if (!listObjectsResponse.isTruncated()) {
                return;
            }
            listRequest = ListObjectsRequest.builder().bucket(bucketName).prefix(ARTIFACTS)
                    .marker(listObjectsResponse.contents().get(listObjectsResponse.contents().size() - 1).key())
                    .build();
        }
    }
}
