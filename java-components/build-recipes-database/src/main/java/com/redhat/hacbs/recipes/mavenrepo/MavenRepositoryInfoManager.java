package com.redhat.hacbs.recipes.mavenrepo;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.recipes.RecipeManager;

public class MavenRepositoryInfoManager implements RecipeManager<MavenRepositoryInfo> {

    public static MavenRepositoryInfoManager INSTANCE = new MavenRepositoryInfoManager();

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    @Override
    public MavenRepositoryInfo parse(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), MavenRepositoryInfo.class);
    }

    @Override
    public void write(MavenRepositoryInfo data, Path file) throws IOException {
        MAPPER.writeValue(file.toFile(), data);
    }
}
