package com.redhat.hacbs.recipes.scm;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipes.RecipeManager;

public class ScmInfoManager implements RecipeManager<ScmInfo> {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    @Override
    public ScmInfo parse(Path file) throws IOException {
        ScmInfo info = MAPPER.readValue(file.toFile(), ScmInfo.class);
        if (info == null) {
            return new ScmInfo(); //can happen on empty file
        }
        return info;
    }

    @Override
    public void write(ScmInfo data, Path file) throws IOException {
        MAPPER.writeValue(file.toFile(), data);
    }
}
