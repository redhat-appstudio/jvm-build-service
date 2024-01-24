package com.redhat.hacbs.recipes.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipes.RecipeManager;

public class BuildRecipeInfoManager implements RecipeManager<BuildRecipeInfo> {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    private static final Logger log = Logger.getLogger(BuildRecipeInfoManager.class.getName());

    @Override
    public BuildRecipeInfo parse(Path file)
            throws IOException {
        log.info("Parsing " + file + " for build recipe information");
        BuildRecipeInfo buildRecipeInfo = null;
        if (file.toFile().length() != 0) {
            buildRecipeInfo = MAPPER.readValue(file.toFile(), BuildRecipeInfo.class);
        }
        return Objects.requireNonNullElseGet(buildRecipeInfo, BuildRecipeInfo::new);
    }

    @Override
    public void write(BuildRecipeInfo data, Path file)
            throws IOException {
        MAPPER.writeValue(file.toFile(), data);
    }
}
