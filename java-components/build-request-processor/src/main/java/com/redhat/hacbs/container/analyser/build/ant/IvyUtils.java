package com.redhat.hacbs.container.analyser.build.ant;

import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;

import org.apache.ivy.Ivy;

/**
 * Utility class for Ivy.
 */
public final class IvyUtils {
    private IvyUtils() {

    }

    /**
     * Loads Ivy with the default settings and returns the new instance.
     *
     * @param settingsFile the settings file
     * @return the new Ivy instance
     */
    public static Ivy loadIvy(Path settingsFile) {
        var ivy = Ivy.newInstance();
        var settings = ivy.getSettings();

        try {
            settings.load(settingsFile.toFile());
        } catch (ParseException | IOException e) {
            throw new RuntimeException(e);
        }

        return ivy;
    }
}
