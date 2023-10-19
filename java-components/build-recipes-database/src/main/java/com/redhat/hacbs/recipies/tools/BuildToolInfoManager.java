package com.redhat.hacbs.recipies.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipies.RecipeManager;

public class BuildToolInfoManager implements RecipeManager<List<BuildToolInfo>> {

    public static BuildToolInfoManager INSTANCE = new BuildToolInfoManager();

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    @Override
    public List<BuildToolInfo> parse(Path file) throws IOException {
        return MAPPER.readerForListOf(BuildToolInfo.class).readValue(file.toFile());
    }

    @Override
    public void write(List<BuildToolInfo> data, Path file) throws IOException {
        MAPPER.writeValue(file.toFile(), data);
    }
}
