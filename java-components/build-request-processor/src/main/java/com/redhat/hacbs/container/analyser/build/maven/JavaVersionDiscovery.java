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
        //TODO: these properties can reference other properties
        //we need a fully resolved maven model
        String target = model.getProperties().getProperty("maven.compiler.target");
        if (target == null) {
            target = model.getProperties().getProperty("maven.compile.target"); //old property name
        }
        String source = model.getProperties().getProperty("maven.compiler.source");
        if (source == null) {
            source = model.getProperties().getProperty("maven.compile.source"); //old property name
        }
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
            if (javaVersion < 7) {
                //JDK5 and lower are JDK8 only
                //JDK6 you can use JDK11, but the build is way more likely to work with JDK8
                if (javaVersion == 6) {
                    return new DiscoveryResult(
                            Map.of(BuildInfo.JDK, new VersionRange("8", "11", "8")), 1);
                } else {
                    return new DiscoveryResult(
                            Map.of(BuildInfo.JDK, new VersionRange("8", "8", "8")), 1);
                }
            }
            return new DiscoveryResult(
                    Map.of(BuildInfo.JDK, new VersionRange(Integer.toString(javaVersion), null, Integer.toString(javaVersion))),
                    1);
        }
        return null;
    }

}
