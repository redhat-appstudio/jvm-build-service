package com.redhat.hacbs.recipes.build;

import static com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.MINIMIZE_QUOTES;
import static com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.SPLIT_LINES;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.logging.Logger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipes.RecipeManager;

public class BuildRecipeInfoManager implements RecipeManager<BuildRecipeInfo> {

    private static final ObjectMapper MAPPER = new ObjectMapper(
            new YAMLFactory().disable(SPLIT_LINES).enable(MINIMIZE_QUOTES))
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    private static final Logger log = Logger.getLogger(BuildRecipeInfoManager.class.getName());

    @Override
    public BuildRecipeInfo parse(InputStream file)
            throws IOException {
        log.info("Parsing " + file + " for build recipe information");
        BuildRecipeInfo buildRecipeInfo = null;
        if (file.available() != 0) {
            buildRecipeInfo = MAPPER.readValue(file, BuildRecipeInfo.class);
        }
        return Objects.requireNonNullElseGet(buildRecipeInfo, BuildRecipeInfo::new);
    }

    @Override
    public void write(BuildRecipeInfo data, OutputStream out)
            throws IOException {
        MAPPER.writeValue(out, data);
    }
}
