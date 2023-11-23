package com.redhat.hacbs.recipies;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class DisabledPluginsManager implements RecipeManager<DisabledPlugins> {

    public static DisabledPluginsManager INSTANCE = new DisabledPluginsManager();

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    @Override
    public DisabledPlugins parse(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), DisabledPlugins.class);
    }

    @Override
    public void write(DisabledPlugins data, Path file) throws IOException {
        MAPPER.writeValue(file.toFile(), data);
    }
}
