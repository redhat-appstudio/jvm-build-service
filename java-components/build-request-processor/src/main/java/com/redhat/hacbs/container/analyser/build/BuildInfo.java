package com.redhat.hacbs.container.analyser.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redhat.hacbs.container.analyser.location.VersionRange;
import com.redhat.hacbs.recipies.build.AdditionalDownload;

public class BuildInfo {

    public static final String JDK = "jdk";
    public static final String MAVEN = "maven";
    public static final String GRADLE = "gradle";
    public static final String SBT = "sbt";
    public static final String ANT = "ant";
    /**
     * List of commands to try. Normally there is only one, but if there is both maven, gradle, sbt, ant
     * present then we might try to invoke them all.
     */
    List<Invocation> invocations = new ArrayList<>();

    /**
     * Additional repositories to use in the rebuild.
     */
    List<String> repositories = new ArrayList<>();

    String enforceVersion;

    long commitTime;

    String preBuildScript;

    String postBuildScript;

    List<AdditionalDownload> additionalDownloads = new ArrayList<>();
    boolean disableSubmodules;
    int additionalMemory;
    List<String> allowedDifferences = new ArrayList<>();

    List<String> gavs = new ArrayList<>();

    String digest;

    String image;

    public String getPreBuildScript() {
        return preBuildScript;
    }

    public BuildInfo setPreBuildScript(String preBuildScript) {
        this.preBuildScript = preBuildScript;
        return this;
    }

    public String getPostBuildScript() {
        return postBuildScript;
    }

    public BuildInfo setPostBuildScript(String postBuildScript) {
        this.postBuildScript = postBuildScript;
        return this;
    }

    public String getEnforceVersion() {
        return enforceVersion;
    }

    public BuildInfo setEnforceVersion(String enforceVersion) {
        this.enforceVersion = enforceVersion;
        return this;
    }

    public long getCommitTime() {
        return commitTime;
    }

    public BuildInfo setCommitTime(long commitTime) {
        this.commitTime = commitTime;
        return this;
    }

    public List<AdditionalDownload> getAdditionalDownloads() {
        return additionalDownloads;
    }

    public BuildInfo setAdditionalDownloads(List<AdditionalDownload> additionalDownloads) {
        this.additionalDownloads = additionalDownloads;
        return this;
    }

    public boolean isDisableSubmodules() {
        return disableSubmodules;
    }

    public BuildInfo setDisableSubmodules(boolean disableSubmodules) {
        this.disableSubmodules = disableSubmodules;
        return this;
    }

    public int getAdditionalMemory() {
        return additionalMemory;
    }

    public BuildInfo setAdditionalMemory(int additionalMemory) {
        this.additionalMemory = additionalMemory;
        return this;
    }

    public List<String> getRepositories() {
        return repositories;
    }

    public BuildInfo setRepositories(List<String> repositories) {
        this.repositories = repositories;
        return this;
    }

    public List<String> getAllowedDifferences() {
        return allowedDifferences;
    }

    public BuildInfo setAllowedDifferences(List<String> allowedDifferences) {
        this.allowedDifferences = allowedDifferences;
        return this;
    }

    public List<String> getGavs() {
        return gavs;
    }

    public BuildInfo setGavs(List<String> gavs) {
        this.gavs = gavs;
        return this;
    }

    public String getDigest() {
        return digest;
    }

    public BuildInfo setDigest(String digest) {
        this.digest = digest;
        return this;
    }

    public String getImage() {
        return image;
    }

    public BuildInfo setImage(String image) {
        this.image = image;
        return this;
    }

    @Override
    public String toString() {
        return "BuildInfo{" +
                ", invocations=" + invocations +
                ", repositories=" + repositories +
                ", enforceVersion='" + enforceVersion + '\'' +
                ", commitTime=" + commitTime +
                ", preBuildScript='" + preBuildScript + '\'' +
                ", postBuildScript='" + postBuildScript + '\'' +
                ", additionalDownloads=" + additionalDownloads +
                ", disableSubmodules=" + disableSubmodules +
                ", additionalMemory=" + additionalMemory +
                ", allowedDifferences=" + allowedDifferences +
                ", image=" + image +
                ", digest=" + digest +
                ", gavs=" + gavs +
                '}';
    }
}
