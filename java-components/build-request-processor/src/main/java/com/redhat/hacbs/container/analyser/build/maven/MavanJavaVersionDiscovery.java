package com.redhat.hacbs.container.analyser.build.maven;

import java.util.Arrays;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.apache.maven.model.Model;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;

import com.redhat.hacbs.container.analyser.build.InvocationBuilder;
import com.redhat.hacbs.container.analyser.build.JavaVersion;

@ApplicationScoped
public class MavanJavaVersionDiscovery {

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

    public static void filterJavaVersions(Model model, InvocationBuilder invocationBuilder) {
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
            if (javaVersion <= 5) {
                invocationBuilder.maxJavaVersion(new JavaVersion("8"));
            } else if (javaVersion == 6) {
                invocationBuilder.maxJavaVersion(new JavaVersion("11"));
            } else {
                invocationBuilder.minJavaVersion(new JavaVersion(Integer.toString(javaVersion)));
            }
        }
    }

}
