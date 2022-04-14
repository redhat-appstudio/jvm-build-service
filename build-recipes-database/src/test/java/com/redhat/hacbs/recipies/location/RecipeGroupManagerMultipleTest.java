package com.redhat.hacbs.recipies.location;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

public class RecipeGroupManagerMultipleTest {
    static RecipeGroupManager manager;

    @BeforeAll
    public static void setup() throws Exception {
        Path opath = Paths.get(RecipeGroupManagerMultipleTest.class.getClassLoader().getResource("override").toURI());
        Path path = Paths.get(RecipeGroupManagerMultipleTest.class.getClassLoader().getResource("test-recipes").toURI());
        manager = new RecipeGroupManager(List.of(new RecipeLayoutManager(opath), new RecipeLayoutManager(path)));
    }

    @Test
    public void testGroupIdBasedRecipe() {
        BuildLocationRequest req = new BuildLocationRequest("io.test", "test", "1.0");
        var result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/test/test.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));

        req = new BuildLocationRequest("io.test.acme", "test-acme", "1.0");
        result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/test-override/test-acme.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testVersionOverride() {
        //the original override should still work
        BuildLocationRequest req = new BuildLocationRequest("io.quarkus", "quarkus-core", "1.0-stuart1");
        var result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/stuartwdouglas/quarkus.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));

        //but now we have added a new one as well
        req = new BuildLocationRequest("io.quarkus", "quarkus-core", "1.0-stuart2");
        result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/stuartwdouglas/quarkus.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testArtifactOverride() {
        //this should still work as normal, it is not overriden
        BuildLocationRequest req = new BuildLocationRequest("io.quarkus", "quarkus-gizmo", "1.0");
        var result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/quarkusio/gizmo.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));

        req = new BuildLocationRequest("io.test", "test-gizmo", "1.0");
        result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/test/gizmo.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testArtifactAndVersionOverride() {
        //same here
        BuildLocationRequest req = new BuildLocationRequest("io.quarkus", "quarkus-gizmo", "1.0-stuart1");
        var result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/stuartwdouglas/gizmo.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));

        req = new BuildLocationRequest("io.test", "test-gizmo", "1.0-stuart1");
        result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/stuartwdouglas/gizmo.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    public void testNoGroupLevelBuild() {
        BuildLocationRequest req = new BuildLocationRequest("io.vertx", "not-real", "1.0");
        var result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    @Disabled("Redirects are currently scoped to the repository")
    public void testArtifactLevelRedirect() {
        BuildLocationRequest req = new BuildLocationRequest("io.vertx", "vertx-web", "1.0");
        var result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/vert-x3/vertx-web.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    @Test
    @Disabled("Redirects are currently scoped to the repository")
    public void testGroupAndArtifactLevelRedirect() {
        var req = new BuildLocationRequest("org.jboss.vertx", "vertx-web", "1.0");
        var result = manager.requestBuildInformation(new ProjectBuildRequest(Set.of(req), Set.of(BuildRecipe.SCM)));
        Assertions.assertEquals("https://github.com/vert-x3/vertx-web.git",
                readScmUrl(result.getRecipes().get(req).get(BuildRecipe.SCM)));
    }

    private String readScmUrl(Path scmPath) {

        Yaml yaml = new Yaml();
        String uri = null;
        try (InputStream in = Files.newInputStream(scmPath)) {
            Map<String, Object> contents = yaml.load(new InputStreamReader(in));
            if (contents != null) {
                uri = (String) contents.get("uri");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (uri == null) {
            return ""; //use the empty string for this case
        }
        return uri;
    }

}
