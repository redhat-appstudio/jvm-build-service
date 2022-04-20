package com.redhat.hacbs.recipies.scm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.yaml.snakeyaml.Yaml;

import com.redhat.hacbs.recipies.RecipeManager;

public class ScmInfoManager implements RecipeManager<ScmInfo> {

    @Override
    public ScmInfo parse(Path file) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(file)) {
            return yaml.loadAs(in, ScmInfo.class);
        }
    }

    @Override
    public void write(ScmInfo data, Path file) throws IOException {
        Yaml yaml = new Yaml();
        String result = yaml.dump(data);
        Files.writeString(file, result, StandardCharsets.UTF_8);

    }
}
