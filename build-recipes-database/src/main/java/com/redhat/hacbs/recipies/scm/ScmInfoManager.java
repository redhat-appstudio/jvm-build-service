package com.redhat.hacbs.recipies.scm;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipies.RecipeManager;

public class ScmInfoManager implements RecipeManager<ScmInfo> {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    @Override
    public ScmInfo parse(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), ScmInfo.class);
    }

    @Override
    public void write(ScmInfo data, Path file) throws IOException {
        MAPPER.writeValue(file.toFile(), data);
    }
}
