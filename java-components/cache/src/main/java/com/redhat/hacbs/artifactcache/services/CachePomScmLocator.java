package com.redhat.hacbs.artifactcache.services;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.jboss.logging.Logger;

import com.redhat.hacbs.recipies.scm.AbstractPomScmLocator;

@ApplicationScoped
public class CachePomScmLocator extends AbstractPomScmLocator {

    private static final Logger log = Logger.getLogger(CachePomScmLocator.class);
    @Inject
    CacheFacade cache;

    @Override
    protected AbstractPomScmLocator.PomClient createPomClient() {
        return new PomClient() {
            @Override
            public Optional<Model> getPom(String group, String artifact, String version) {
                var response = cache.getArtifactFile("default", group.replace(".", "/"), artifact, version, artifact
                        + "-" + version + ".pom", false);
                if (!response.isPresent()) {
                    return Optional.empty();
                }
                try {
                    try (Reader pomReader = new InputStreamReader(response.get().getData())) {
                        MavenXpp3Reader reader = new MavenXpp3Reader();
                        Model model = reader.read(pomReader);
                        return Optional.of(model);
                    }

                } catch (Exception e) {
                    log.errorf(e, "Failed to get pom for %s:%s:%s", group, artifact, version);
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
