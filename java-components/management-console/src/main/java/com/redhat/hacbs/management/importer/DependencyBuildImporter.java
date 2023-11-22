package com.redhat.hacbs.management.importer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.management.model.AdditionalDownload;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.BuildIdentifier;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.ScmRepository;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuildSpec;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildspec.Scm;

import io.quarkus.logging.Log;
import io.quarkus.panache.common.Parameters;

@ApplicationScoped
public class DependencyBuildImporter {

    @ConfigProperty(name = "bucket.name")
    String s3Bucket;

    @Transactional
    public void doImport(DependencyBuild dependencyBuild) {
        if (dependencyBuild.getStatus() == null || dependencyBuild.getStatus().getState() == null) {
            return;
        }
        Log.infof("importing dependency build %s for %s:%s", dependencyBuild.getMetadata().getName(),
                dependencyBuild.getSpec().getScm().getScmURL(), dependencyBuild.getSpec().getScm().getTag());
        boolean complete = Objects.equals(dependencyBuild.getStatus().getState(), ModelConstants.DEPENDENCY_BUILD_COMPLETE);
        boolean failed = Objects.equals(dependencyBuild.getStatus().getState(), ModelConstants.DEPENDENCY_BUILD_FAILED);
        boolean contaminated = Objects.equals(dependencyBuild.getStatus().getState(),
                ModelConstants.DEPENDENCY_BUILD_CONTAMINATED);
        if (!complete &&
                !failed &&
                !contaminated) {
            //don't import in-progress builds
            return;
        }

        DependencyBuildSpec spec = dependencyBuild.getSpec();
        Scm scm = spec.getScm();
        ScmRepository repository = ScmRepository.find("url", scm.getScmURL()).firstResult();
        if (repository == null) {
            repository = new ScmRepository();
            repository.url = scm.getScmURL();
            repository.persist();
        }
        BuildIdentifier identifier = BuildIdentifier.findORCreate(scm.getScmURL(), scm.getTag(), scm.getCommitHash(),
                scm.getPath(), dependencyBuild.getMetadata().getName());

        StoredDependencyBuild storedBuild = StoredDependencyBuild
                .find("buildIdentifier = :buildIdentifier and uid = :uid",
                        Parameters.with("buildIdentifier", identifier).and("uid", dependencyBuild.getMetadata().getUid()))
                .firstResult();
        if (storedBuild == null) {
            storedBuild = new StoredDependencyBuild();
            storedBuild.buildIdentifier = identifier;
            storedBuild.uid = dependencyBuild.getMetadata().getUid();
        }
        if (s3Bucket != null) {
            storedBuild.buildYamlUrl = "s3://" + s3Bucket + "/builds/" + dependencyBuild.getMetadata().getName() + "/"
                    + dependencyBuild.getMetadata().getUid() + ".yaml";
        }
        storedBuild.succeeded = !failed;
        storedBuild.contaminated = contaminated;
        storedBuild.version = spec.getVersion();
        storedBuild.creationTime = Instant.parse(dependencyBuild.getMetadata().getCreationTimestamp());
        storedBuild.producedArtifacts = new ArrayList<>();
        if (dependencyBuild.getStatus().getDeployedArtifacts() != null) {
            for (var i : dependencyBuild.getStatus().getDeployedArtifacts()) {
                storedBuild.producedArtifacts.add(MavenArtifact.forGav(i));
            }
        }
        storedBuild.buildAttempts = new ArrayList<>();

        if (s3Bucket != null) {
            //todo we just assume the logs are present
            storedBuild.buildDiscoveryUrl = "s3://" + s3Bucket + "/build-logs/" + dependencyBuild.getMetadata().getName() + "/"
                    + dependencyBuild.getMetadata().getUid()
                    + "/build-discovery.log";
        }
        if (dependencyBuild.getStatus().getBuildAttempts() != null) {
            for (var i : dependencyBuild.getStatus().getBuildAttempts()) {
                BuildAttempt attempt = new BuildAttempt();
                attempt.dependencyBuild = storedBuild;
                storedBuild.buildAttempts.add(attempt);
                attempt.jdk = i.getBuildRecipe().getJavaVersion();
                attempt.mavenVersion = i.getBuildRecipe().getToolVersions().get("maven");
                attempt.gradleVersion = i.getBuildRecipe().getToolVersions().get("gradle");
                attempt.sbtVersion = i.getBuildRecipe().getToolVersions().get("sbt");
                attempt.antVersion = i.getBuildRecipe().getToolVersions().get("ant");
                attempt.buildId = i.getBuildId();
                attempt.tool = i.getBuildRecipe().getTool();
                attempt.builderImage = i.getBuildRecipe().getImage();
                attempt.commandLine(i.getBuildRecipe().getCommandLine());
                attempt.preBuildScript = i.getBuildRecipe().getPreBuildScript();
                attempt.postBuildScript = i.getBuildRecipe().getPostBuildScript();
                attempt.enforceVersion = i.getBuildRecipe().getEnforceVersion();
                attempt.disableSubModules = Boolean.TRUE.equals(i.getBuildRecipe().getDisableSubmodules());
                attempt.repositories = i.getBuildRecipe().getRepositories() == null ? null
                        : i.getBuildRecipe().getRepositories().stream().collect(Collectors.joining(","));
                attempt.allowedDifferences = i.getBuildRecipe().getAllowedDifferences() == null ? null
                        : i.getBuildRecipe().getAllowedDifferences().stream()
                                .collect(Collectors.joining("\n"));
                attempt.successful = Boolean.TRUE.equals(i.getBuild().getSucceeded());
                if (i.getBuildRecipe().getAdditionalDownloads() != null) {
                    attempt.additionalDownloads = i.getBuildRecipe().getAdditionalDownloads().stream().map(s -> {
                        AdditionalDownload download = new AdditionalDownload();
                        download.buildAttempt = attempt;
                        download.binaryPath = s.getBinaryPath();
                        download.fileName = s.getFileName();
                        download.packageName = s.getPackageName();
                        download.sha256 = s.getSha256();
                        download.uri = s.getUri();
                        download.fileType = s.getType();
                        return download;
                    }).collect(Collectors.toList());
                }

                if (s3Bucket != null) {
                    //todo we just assume the logs are present
                    attempt.buildLogsUrl = "s3://" + s3Bucket + "/build-logs/" + dependencyBuild.getMetadata().getName() + "/"
                            + dependencyBuild.getMetadata().getUid()
                            + "/" + i.getBuild().getPipelineName() + ".log";
                    attempt.buildPipelineUrl = "s3://" + s3Bucket + "/build-pipelines/"
                            + dependencyBuild.getMetadata().getName()
                            + "/" + dependencyBuild.getMetadata().getUid() + "/" + i.getBuild().getPipelineName() + ".yaml";
                }
                if (i.getBuild().getResults() != null) {
                    attempt.outputImage = i.getBuild().getResults().getImage();
                    attempt.outputImageDigest = i.getBuild().getResults().getImageDigest();
                    attempt.passedVerification = Boolean.TRUE.equals(i.getBuild().getResults().getVerified());
                    attempt.hermeticBuilderImage = i.getBuild().getResults().getHermeticBuildImage();
                }
            }
        }

        storedBuild.persist();

    }

}
