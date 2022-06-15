package com.redhat.hacbs.recipies.build;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipies.RecipeManager;

public class BuildRecipeInfoManager implements RecipeManager<BuildRecipeInfo> {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    @Override
    public BuildRecipeInfo parse(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), BuildRecipeInfo.class);
    }

    @Override
    public void write(BuildRecipeInfo data, Path file) throws IOException {
        MAPPER.writeValue(file.toFile(), data);
    }
}
