package com.redhat.hacbs.recipies.disabledplugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipies.RecipeManager;

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
    public DisabledPlugins parse(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), DisabledPlugins.class);
    }

    @Override
    public void write(DisabledPlugins data, Path file) throws IOException {
        MAPPER.writeValue(file.toFile(), data);
    }
}
