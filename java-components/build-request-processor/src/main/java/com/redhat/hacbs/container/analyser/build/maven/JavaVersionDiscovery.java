package com.redhat.hacbs.container.analyser.build.maven;

import java.nio.file.Path;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;

import org.apache.maven.model.Model;

import com.redhat.hacbs.container.analyser.build.BuildInfo;
import com.redhat.hacbs.container.analyser.build.DiscoveryResult;
import com.redhat.hacbs.container.analyser.build.JavaVersion;
import com.redhat.hacbs.container.analyser.location.VersionRange;

@ApplicationScoped
public class JavaVersionDiscovery implements MavenDiscoveryTask {
    @Override
    public DiscoveryResult discover(Model model, Path checkout) {
        String target = model.getProperties().getProperty("maven.compiler.target");
        String source = model.getProperties().getProperty("maven.compiler.source");
        int javaVersion = -1;
        if (target != null) {
            javaVersion = JavaVersion.toVersion(target);
        }
        if (source != null) {
            var parsed = JavaVersion.toVersion(source);
            if (parsed > javaVersion) {
                javaVersion = parsed;
            }
        }
        if (javaVersion > 0) {
            return new DiscoveryResult(
                    Map.of(BuildInfo.JDK, new VersionRange(Integer.toString(javaVersion), null, Integer.toString(javaVersion))),
                    1);
        }
        return null;
    }

}
