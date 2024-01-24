package com.redhat.hacbs.recipes.build;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.Test;

class BuildRecipeInfoManagerTest {

    @Test
    void parse()
            throws IOException, URISyntaxException {
        BuildRecipeInfoManager buildRecipeInfoManager = new BuildRecipeInfoManager();
        var result = buildRecipeInfoManager.parse(Path.of(
                Objects.requireNonNull(
                        BuildRecipeInfoManagerTest.class.getClassLoader().getResource("build.yaml")).toURI()));
        assertNotNull(result);
    }
}
