package com.redhat.hacbs.management.importer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.management.model.AdditionalDownload;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.BuildIdentifier;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.ScmRepository;
import com.redhat.hacbs.management.model.ShadingDetails;
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

    @ConfigProperty(name = "MAVEN_REPOSITORY")
    Optional<String> mavenRepo;

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
                .find("buildIdentifier = :buildIdentifier",
                        Parameters.with("buildIdentifier", identifier))
                .firstResult();
        if (storedBuild == null) {
            storedBuild = new StoredDependencyBuild();
            storedBuild.buildIdentifier = identifier;
        }
        if (s3Bucket != null) {
            storedBuild.buildYamlUrl = "s3://" + s3Bucket + "/builds/" + dependencyBuild.getMetadata().getName() + "/"
                    + dependencyBuild.getMetadata().getUid() + ".yaml";
        }
        storedBuild.succeeded = !failed;
        storedBuild.contaminated = contaminated;
        storedBuild.version = spec.getVersion();
        storedBuild.creationTimestamp = Instant.parse(dependencyBuild.getMetadata().getCreationTimestamp());
        if (storedBuild.buildAttempts == null) {
            storedBuild.buildAttempts = new ArrayList<>();
        }

        if (s3Bucket != null) {
            //todo we just assume the logs are present
            storedBuild.buildDiscoveryUrl = "s3://" + s3Bucket + "/build-logs/" + dependencyBuild.getMetadata().getName() + "/"
                    + dependencyBuild.getMetadata().getUid()
                    + "/build-discovery.log";
            storedBuild.deployLogsUrl = "s3://" + s3Bucket + "/build-logs/" + dependencyBuild.getMetadata().getName() + "/"
                    + dependencyBuild.getMetadata().getUid()
                    + "/deploy.log";
        }
        if (dependencyBuild.getStatus().getBuildAttempts() != null) {
            for (var i : dependencyBuild.getStatus().getBuildAttempts()) {
                BuildAttempt attempt = null;
                for (var ba : storedBuild.buildAttempts) {
                    if (ba.buildId != null && Objects.equals(ba.buildId, i.getBuildId())) {
                        //existing one, just update it
                        attempt = ba;
                        Log.infof("Existing maven repo is %s", attempt.mavenRepository);
                    }
                }
                if (attempt == null) {
                    attempt = new BuildAttempt();
                    storedBuild.buildAttempts.add(attempt);
                }
                attempt.dependencyBuild = storedBuild;
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
                        : String.join("\n", i.getBuildRecipe().getAllowedDifferences());
                attempt.successful = Boolean.TRUE.equals(i.getBuild().getSucceeded());
                var finalAttempt = attempt;
                if (i.getBuildRecipe().getAdditionalDownloads() != null) {
                    attempt.additionalDownloads = i.getBuildRecipe().getAdditionalDownloads().stream().map(s -> {
                        AdditionalDownload download = new AdditionalDownload();
                        download.buildAttempt = finalAttempt;
                        download.binaryPath = s.getBinaryPath();
                        download.fileName = s.getFileName();
                        download.packageName = s.getPackageName();
                        download.sha256 = s.getSha256();
                        download.uri = s.getUri();
                        download.fileType = s.getType();
                        return download;
                    }).collect(Collectors.toList());
                }
                Log.infof("Maven repo is %s", mavenRepo);
                if (mavenRepo.isPresent()) {
                    finalAttempt.mavenRepository = mavenRepo.get().replace("/repository/maven-releases",
                            "/service/rest/repository/browse/maven-releases");
                    Log.infof("Set maven repo to %s", finalAttempt.mavenRepository);
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
                attempt.diagnosticDockerFile = i.getBuild().getDiagnosticDockerFile();
                if (i.getBuild().getStartTime() != null) {
                    attempt.startTime = Instant.ofEpochSecond(i.getBuild().getStartTime());
                }
                if (i.getBuild().getResults() != null) {
                    attempt.outputImage = i.getBuild().getResults().getImage();
                    attempt.outputImageDigest = i.getBuild().getResults().getImageDigest();
                    attempt.passedVerification = Boolean.TRUE.equals(i.getBuild().getResults().getVerified());
                    attempt.hermeticBuilderImage = i.getBuild().getResults().getHermeticBuildImage();
                    attempt.upstreamDifferences = i.getBuild().getResults().getVerificationFailures();
                    if (i.getBuild().getResults().getGitArchive() != null) {
                        attempt.gitArchiveTag = i.getBuild().getResults().getGitArchive().getTag();
                        attempt.gitArchiveUrl = i.getBuild().getResults().getGitArchive().getUrl();
                        attempt.gitArchiveSha = i.getBuild().getResults().getGitArchive().getSha();
                    }

                    if (i.getBuild().getResults().getContaminates() != null) {
                        if (attempt.shadingDetails == null) {
                            attempt.shadingDetails = new ArrayList<>();
                        } else {
                            attempt.shadingDetails.clear();
                        }
                        attempt.contaminated = i.getBuild().getResults().getContaminated() != null
                                && i.getBuild().getResults().getContaminated();
                        for (var ct : i.getBuild().getResults().getContaminates()) {
                            ShadingDetails d = new ShadingDetails();
                            d.contaminant = MavenArtifact.forGav(ct.getGav());
                            d.contaminatedArtifacts = new ArrayList<>();
                            for (var j : ct.getContaminatedArtifacts()) {
                                d.contaminatedArtifacts.add(MavenArtifact.forGav(j));
                            }
                            d.buildId = ct.getBuildId();
                            d.allowed = ct.getAllowed() == null ? false : ct.getAllowed();
                            d.rebuildAvailable = ct.getRebuildAvailable() == null ? false : ct.getRebuildAvailable();
                            d.source = ct.getSource();
                            d.buildAttempt = attempt;
                            attempt.shadingDetails.add(d);
                        }
                    }
                }

                attempt.producedArtifacts = new ArrayList<>();
                if (dependencyBuild.getStatus().getDeployedArtifacts() != null) {
                    for (var at : dependencyBuild.getStatus().getDeployedArtifacts()) {
                        attempt.producedArtifacts.add(MavenArtifact.forGav(at));
                    }
                }
            }
        }

        storedBuild.persist();

    }
}
