package com.redhat.hacbs.container.analyser.build.maven;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.apache.maven.model.Model;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;

import com.redhat.hacbs.container.analyser.build.BuildInfo;
import com.redhat.hacbs.container.analyser.build.DiscoveryResult;
import com.redhat.hacbs.container.analyser.build.JavaVersion;
import com.redhat.hacbs.container.analyser.location.VersionRange;

@ApplicationScoped
public class JavaVersionDiscovery implements MavenDiscoveryTask {

    public static String interpolate(String value, Model model) {
        if (value != null && value.contains("${")) {
            StringSearchInterpolator interpolator = new StringSearchInterpolator();
            List<String> pomPrefixes = Arrays.asList("pom.", "project.");
            interpolator.addValueSource(new PrefixedObjectValueSource(pomPrefixes, model, false));
            interpolator.addValueSource(new PropertiesBasedValueSource(model.getProperties()));
            interpolator.addValueSource(new ObjectBasedValueSource(model));
            try {
                value = interpolator.interpolate(value, new PrefixAwareRecursionInterceptor(pomPrefixes));
            } catch (InterpolationException e) {
                throw new RuntimeException(
                        "Failed to interpolate " + value + " for project " + model.getId(), e);
            }
        }
        return value;
    }

    @Override
    public DiscoveryResult discover(Model model, Path checkout) {
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
            target = interpolate(target, model);
            javaVersion = JavaVersion.toVersion(target);
        }
        if (source != null) {
            source = interpolate(source, model);
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
                            Map.of(BuildInfo.JDK, new VersionRange("7", "11", "8")), 1);
                } else {
                    return new DiscoveryResult(
                            Map.of(BuildInfo.JDK, new VersionRange("7", "8", "8")), 1);
                }
            }
            return new DiscoveryResult(
                    Map.of(BuildInfo.JDK, new VersionRange(Integer.toString(javaVersion), null, Integer.toString(javaVersion))),
                    1);
        }
        return null;
    }

}
