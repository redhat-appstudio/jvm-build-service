package com.redhat.hacbs.recipes.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipes.RecipeManager;

public class BuildToolInfoManager implements RecipeManager<List<BuildToolInfo>> {

    public static BuildToolInfoManager INSTANCE = new BuildToolInfoManager();

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    @Override
    public List<BuildToolInfo> parse(InputStream file) throws IOException {
        return MAPPER.readerForListOf(BuildToolInfo.class).readValue(file);
    }

    @Override
    public void write(List<BuildToolInfo> data, OutputStream out) throws IOException {
        MAPPER.writeValue(out, data);
    }
}
