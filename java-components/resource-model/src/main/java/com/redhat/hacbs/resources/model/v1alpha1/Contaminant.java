package com.redhat.hacbs.resources.model.v1alpha1;

import java.util.List;

public class Contaminant {
    private String gav;
    private List<String> contaminatedArtifacts;

    public Contaminant() {
    }

    public Contaminant(String gav, List<String> contaminatedArtifacts) {
        this.gav = gav;
        this.contaminatedArtifacts = contaminatedArtifacts;
    }

    public String getGav() {
        return gav;
    }

    public Contaminant setGav(String gav) {
        this.gav = gav;
        return this;
    }

    public List<String> getContaminatedArtifacts() {
        return contaminatedArtifacts;
    }

    public Contaminant setContaminatedArtifacts(List<String> contaminatedArtifacts) {
        this.contaminatedArtifacts = contaminatedArtifacts;
        return this;
    }

    @Override
    public String toString() {
        return "Contaminant{" +
                "gav='" + gav + '\'' +
                ", contaminatedArtifacts=" + contaminatedArtifacts +
                '}';
    }
}
