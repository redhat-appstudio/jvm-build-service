package com.redhat.hacbs.recipes.mavenrepo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipes.RecipeManager;

public class MavenRepositoryInfoManager implements RecipeManager<MavenRepositoryInfo> {

    public static MavenRepositoryInfoManager INSTANCE = new MavenRepositoryInfoManager();

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    @Override
    public MavenRepositoryInfo parse(InputStream file) throws IOException {
        return MAPPER.readValue(file, MavenRepositoryInfo.class);
    }

    @Override
    public void write(MavenRepositoryInfo data, OutputStream out) throws IOException {
        MAPPER.writeValue(out, data);
    }
}
