package com.redhat.hacbs.artifactcache.services;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import com.redhat.hacbs.recipes.scm.AbstractPomScmLocator;

import io.quarkus.logging.Log;

@ApplicationScoped
public class CachePomScmLocator extends AbstractPomScmLocator {

    @Inject
    CacheFacade cache;

    @Override
    protected AbstractPomScmLocator.PomClient createPomClient() {
        return new PomClient() {
            @Override
            public Optional<Model> getPom(String group, String artifact, String version) {
                var response = cache.getArtifactFile("default", group.replace(".", "/"), artifact, version, artifact
                        + "-" + version + ".pom", false);
                if (response.isEmpty()) {
                    return Optional.empty();
                }
                try {
                    try (Reader pomReader = new InputStreamReader(response.get().getData())) {
                        MavenXpp3Reader reader = new MavenXpp3Reader();
                        Model model = reader.read(pomReader);
                        return Optional.of(model);
                    }

                } catch (Exception e) {
                    Log.errorf(e, "Failed to get pom for %s:%s:%s", group, artifact, version);
                    return Optional.empty();
                } finally {
                    try {
                        response.get().close();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            @Override
            public void close() {

            }
        };
    }
}
