package com.redhat.hacbs.recipies.location;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.scm.ScmInfo;

public class RecipeGroupManagerSingleTest {
    static RecipeGroupManager manager;

    @BeforeAll
    public static void setup() throws Exception {
        Path path = Paths.get(RecipeGroupManagerSingleTest.class.getClassLoader().getResource("test-recipes").toURI());
        manager = new RecipeGroupManager(List.of(new RecipeLayoutManager(path)));
    }

    @Test
    public void testGroupIdBasedRecipe() {
        GAV req = new GAV("io.quarkus", "quarkus-core", "1.0");
        var result = manager.requestArtifactInformation(new ArtifactInfoRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/quarkusio/quarkus.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));

        req = new GAV("io.quarkus.security", "quarkus-security", "1.0");
        result = manager.requestArtifactInformation(new ArtifactInfoRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/quarkusio/quarkus-security.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testVersionOverride() {
        GAV req = new GAV("io.quarkus", "quarkus-core", "1.0-alpha1");
        var result = manager.requestArtifactInformation(new ArtifactInfoRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/stuartwdouglas/quarkus.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
        req = new GAV("io.quarkus", "quarkus-core", "0.9");
        result = manager.requestArtifactInformation(new ArtifactInfoRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/stuartwdouglas/quarkus.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testArtifactOverride() {
        GAV req = new GAV("io.quarkus", "quarkus-gizmo", "1.0");
        var result = manager.requestArtifactInformation(new ArtifactInfoRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/quarkusio/gizmo.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testArtifactAndVersionOverride() {
        GAV req = new GAV("io.quarkus", "quarkus-gizmo", "1.0-alpha1");
        var result = manager.requestArtifactInformation(new ArtifactInfoRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/stuartwdouglas/gizmo.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
        req = new GAV("io.quarkus", "quarkus-gizmo", "0.9");
        result = manager.requestArtifactInformation(new ArtifactInfoRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/stuartwdouglas/gizmo.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testNoGroupLevelBuild() {
        GAV req = new GAV("io.vertx", "not-real", "1.0");
        var result = manager.requestArtifactInformation(new ArtifactInfoRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testBuildInfoRecipe() throws IOException {
        var result = manager.requestBuildInformation(
                new BuildInfoRequest("https://github.com/quarkusio/quarkus.git", "1.0", Set.of(BuildRecipe.BUILD)));
        Assertions.assertEquals(List.of("-DskipDocs=true"),
                BuildRecipe.BUILD.getHandler().parse(result.getData().get(BuildRecipe.BUILD)).getAdditionalArgs());

    }

    private String readScmUrl(Path scmPath) {
        if (scmPath == null) {
            return "";
        }
        try {
            ScmInfo parse = BuildRecipe.SCM.getHandler().parse(scmPath);
            if (parse == null) {
                return "";
            }
            return parse.getUri();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
