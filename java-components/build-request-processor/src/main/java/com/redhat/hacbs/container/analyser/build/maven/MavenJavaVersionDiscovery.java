package com.redhat.hacbs.container.analyser.build.maven;

import static com.redhat.hacbs.container.analyser.build.JavaVersion.JAVA_11;
import static com.redhat.hacbs.container.analyser.build.JavaVersion.JAVA_8;
import static com.redhat.hacbs.container.verifier.MavenUtils.getCompilerSource;
import static com.redhat.hacbs.container.verifier.MavenUtils.getCompilerTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import com.redhat.hacbs.container.analyser.build.InvocationBuilder;
import com.redhat.hacbs.container.analyser.build.JavaVersion;

import io.quarkus.logging.Log;

@ApplicationScoped
public class MavenJavaVersionDiscovery {

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

    public static void filterJavaVersions(Path pomFile, Model model, InvocationBuilder invocationBuilder)
            throws IOException, XmlPullParserException {
        String target = model.getProperties().getProperty("maven.compiler.target");
        if (target == null) {
            target = model.getProperties().getProperty("maven.compile.target"); //old property name

            if (target == null) {
                target = getCompilerTarget(model).orElse(null);
            }
        }
        String source = model.getProperties().getProperty("maven.compiler.source");
        if (source == null) {
            source = model.getProperties().getProperty("maven.compile.source"); //old property name

            if (source == null) {
                source = getCompilerSource(model).orElse(null);
            }
        }
        int javaVersion = -1;
        if (target != null) {
            target = interpolate(target, model);
            javaVersion = JavaVersion.toVersion(target);
            Log.infof("Discovered Java target %s", javaVersion);
        }
        if (source != null) {
            source = interpolate(source, model);
            var parsed = JavaVersion.toVersion(source);
            Log.infof("Discovered Java source %s", parsed);
            if (parsed > javaVersion) {
                javaVersion = parsed;
            }
        }
        if (javaVersion > 0) {
            if (javaVersion <= 5) {
                invocationBuilder.maxJavaVersion(JAVA_8);
            } else if (javaVersion == 6) {
                invocationBuilder.maxJavaVersion(JAVA_11);
            } else {
                invocationBuilder.minJavaVersion(new JavaVersion(Integer.toString(javaVersion)));
            }
        }

        for (var module : model.getModules()) {
            try {
                var modulePath = pomFile.getParent().resolve(module);
                var modulePomFile = modulePath.resolve("pom.xml");

                try (var pomReader = Files.newBufferedReader(modulePomFile)) {
                    var reader = new MavenXpp3Reader();
                    var submodel = reader.read(pomReader);
                    filterJavaVersions(modulePomFile, submodel, invocationBuilder);
                }
            } catch (Exception e) {
                Log.errorf(e, "Failed to handle module %s", module);
            }
        }
    }
}
