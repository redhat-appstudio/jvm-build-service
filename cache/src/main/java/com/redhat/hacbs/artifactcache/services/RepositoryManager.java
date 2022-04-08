package com.redhat.hacbs.artifactcache.services;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.enterprise.inject.Produces;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.artifactcache.services.mavenclient.MavenClient;

import io.quarkus.logging.Log;

/**
 * Class that consumes the repository config and creates the runtime representation of the repositories
 */
class RepositoryManager {

    private static final String REPOSITORY = "repository.";
    private static final String URL = ".url";
    private static final String TYPE = ".type";

    @Produces
    List<Repository> createRepositoryInfo(@ConfigProperty(name = "remote-repository-list") List<String> repositories,
            Config config) {
        List<Repository> ret = new ArrayList<>();
        for (String repo : repositories) {
            Optional<URI> uri = config.getOptionalValue(REPOSITORY + repo + URL, URI.class);
            Optional<RepositoryType> optType = config.getOptionalValue(REPOSITORY + repo + TYPE, RepositoryType.class);
            if (uri.isPresent()) {
                Log.infof("Repository %s added with URI %s", repo, uri.get());
                RepositoryType type = optType.orElse(RepositoryType.MAVEN2);
                RepositoryClient client;
                switch (type) {
                    case MAVEN2:
                        //TODO: custom SSL config for internal certs
                        client = MavenClient.of(repo, uri.get());
                        break;
                    case S3:
                        client = new RepositoryClient() {
                            @Override
                            public Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact,
                                    String version,
                                    String target) {
                                throw new RuntimeException("NOT IMPLEMENTED YET");
                            }

                            @Override
                            public Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target) {
                                throw new RuntimeException("NOT IMPLEMENTED YET");
                            }
                        };
                        break;
                    default:
                        throw new RuntimeException("Unknown type: " + type);
                }
                ret.add(new Repository(repo, uri.get(), type, client));
            } else {
                Log.warnf("Repository %s was listed but has no config and will be ignored", repo);
            }
        }
        if (ret.isEmpty()) {
            throw new IllegalStateException("No configured repositories present, repository cache cannot function");
        }
        return ret;
    }
}
