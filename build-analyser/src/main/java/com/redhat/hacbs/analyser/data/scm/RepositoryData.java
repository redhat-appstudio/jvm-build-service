package com.redhat.hacbs.analyser.data.scm;

import java.util.ArrayList;
import java.util.List;

public class RepositoryData {

    private List<Repository> repositories = new ArrayList<>();

    public List<Repository> getRepositories() {
        return repositories;
    }

    public void setRepositories(List<Repository> repositories) {
        this.repositories = repositories;
    }
}
