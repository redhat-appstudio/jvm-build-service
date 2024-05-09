package com.redhat.hacbs.recipes.disabledplugins;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipes.RecipeManager;

public class DisabledPluginsManager implements RecipeManager<DisabledPlugins> {
    public static final String DISABLED_PLUGINS_MAVEN = "maven.yaml";

    public static final String DISABLED_PLUGINS_GRADLE = "gradle.yaml";

    public static DisabledPluginsManager INSTANCE = new DisabledPluginsManager();

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    public List<String> getDisabledPlugins(Path file) throws IOException {
        return parse(file).getDisabledPlugins();
    }

    @Override
    public DisabledPlugins parse(InputStream file) throws IOException {
        return MAPPER.readValue(file, DisabledPlugins.class);
    }

    @Override
    public void write(DisabledPlugins data, OutputStream out) throws IOException {
        MAPPER.writeValue(out, data);
    }
}
