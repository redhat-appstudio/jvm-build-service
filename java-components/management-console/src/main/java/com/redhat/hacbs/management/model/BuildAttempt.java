package com.redhat.hacbs.management.model;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Arc;
import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class BuildAttempt extends PanacheEntity {

    public String jdk;
    public String mavenVersion;
    public String gradleVersion;
    public String sbtVersion;
    public String antVersion;
    public String tool;

    public String buildId;
    @Column(length = -1)
    public String builderImage;
    @Column(length = -1)
    public String preBuildImage;
    @Column(length = -1)
    public String outputImage;
    @Column
    public String outputImageDigest;
    @Column(length = -1)
    public String commandLine;
    @Column(length = -1)
    public String preBuildScript;
    @Column(length = -1)
    public String postBuildScript;
    public String enforceVersion;
    public boolean disableSubModules;
    public int additionalMemory;
    public String repositories;
    @Column(length = -1)
    public String allowedDifferences;

    public String buildPipelineUrl;

    public String mavenRepository;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "buildAttempt")
    public List<AdditionalDownload> additionalDownloads;

    public boolean successful;
    public boolean passedVerification;

    @ManyToOne(optional = false)
    @JoinColumn(nullable = false)
    public StoredDependencyBuild dependencyBuild;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "build")
    public List<BuildFile> storedBuildResults;

    @Column(length = -1)
    public String upstreamDifferences;

    public String gitArchiveSha;
    @Column(length = -1)
    public String gitArchiveTag;
    @Column(length = -1)
    public String gitArchiveUrl;
    @Column(length = -1)
    public String diagnosticDockerFile;

    @Column
    @Temporal(TemporalType.TIMESTAMP)
    public Instant startTime;

    public boolean contaminated;

    @ManyToMany(cascade = CascadeType.ALL)
    public List<MavenArtifact> producedArtifacts;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "buildAttempt", orphanRemoval = true)
    public List<ShadingDetails> shadingDetails;

    //this is pretty yuck, but we don't want a whole new table to store a List<String>
    public void commandLine(List<String> commandLine) {
        var mapper = Arc.container().instance(ObjectMapper.class);
        try {
            this.commandLine = mapper.get().writeValueAsString(commandLine);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> commandLine() {
        var mapper = Arc.container().instance(ObjectMapper.class);
        try {
            return mapper.get().readerForListOf(String.class).readValue(commandLine);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

}
