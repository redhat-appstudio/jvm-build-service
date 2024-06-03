package com.redhat.hacbs.container.analyser.build.maven;

import static com.redhat.hacbs.container.analyser.build.JavaVersion.JAVA_11;
import static com.redhat.hacbs.container.analyser.build.JavaVersion.JAVA_8;
import static com.redhat.hacbs.container.verifier.MavenUtils.getCompilerSource;
import static com.redhat.hacbs.container.verifier.MavenUtils.getCompilerTarget;

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

import com.redhat.hacbs.container.analyser.build.InvocationBuilder;
import com.redhat.hacbs.container.analyser.build.JavaVersion;

import io.quarkus.logging.Log;

@ApplicationScoped
public class MavenJavaVersionDiscovery {

    public static String interpolate(String value, List<Model> models) {
        if (value != null && value.contains("${")) {
            StringSearchInterpolator interpolator = new StringSearchInterpolator();
            List<String> pomPrefixes = Arrays.asList("pom.", "project.");
            for (Model model : models) {
                interpolator.addValueSource(new PrefixedObjectValueSource(pomPrefixes, model, false));
                interpolator.addValueSource(new PropertiesBasedValueSource(model.getProperties()));
                interpolator.addValueSource(new ObjectBasedValueSource(model));
            }
            try {
                value = interpolator.interpolate(value, new PrefixAwareRecursionInterceptor(pomPrefixes));
            } catch (InterpolationException e) {
                throw new RuntimeException(
                        "Failed to interpolate " + value + " for project " + models.get(0).getId(), e);
            }
        }
        return value;
    }

    public static void filterJavaVersions(Path pomFile, List<Model> models, InvocationBuilder invocationBuilder) {
        //if the toolchains plugin is configured we don't filter anything
        Model model = models.get(models.size() - 1);
        if (model.getBuild() != null && model.getBuild().getPlugins() != null) {
            for (var i : model.getBuild().getPlugins()) {
                if (i.getArtifactId().equals("maven-toolchains-plugin")) {
                    return;
                }
            }
        }

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
            target = interpolate(target, models);
            javaVersion = JavaVersion.toVersion(target);
            Log.infof("Discovered Java target %s", javaVersion);
        }
        if (source != null) {
            source = interpolate(source, models);
            var parsed = JavaVersion.toVersion(source);
            Log.infof("Discovered Java source %s", parsed);
            if (parsed > javaVersion) {
                javaVersion = parsed;
            }
        }

        for (var module : model.getModules()) {
            try {
                var modulePath = pomFile.getParent().resolve(module);
                var modulePomFile = modulePath.resolve("pom.xml");

                try (var pomReader = Files.newBufferedReader(modulePomFile)) {
                    var reader = new MavenXpp3Reader();
                    var submodel = reader.read(pomReader);
                    models.add(submodel);
                    filterJavaVersions(modulePomFile, models, invocationBuilder);
                }
            } catch (Exception e) {
                Log.errorf(e, "Failed to handle module %s", module);
            }
        }

        filterJavaVersions(invocationBuilder, javaVersion);
    }

    public static void filterJavaVersions(InvocationBuilder invocationBuilder, String javaVersion) {
        filterJavaVersions(invocationBuilder, JavaVersion.toVersion(javaVersion));
    }

    public static void filterJavaVersions(InvocationBuilder invocationBuilder, int javaVersion) {
        if (javaVersion > 0) {
            if (javaVersion <= 5) {
                invocationBuilder.maxJavaVersion(JAVA_8);
                Log.infof("Set max Java version to %s", JAVA_8);
            } else if (javaVersion == 6) {
                invocationBuilder.maxJavaVersion(JAVA_11);
                Log.infof("Set max Java version to %s", JAVA_11);
            } else {
                var version = new JavaVersion(Integer.toString(javaVersion));
                invocationBuilder.minJavaVersion(version);
                Log.infof("Set min Java version to %s", version);
            }
        }
    }
}
