package com.redhat.hacbs.recipies.location;

import java.util.Objects;

/**
 * Represents a recipe file (e.g. scm.yaml) that contains build information
 *
 * This is not an enum to allow for extensibility
 */
public class BuildRecipe {

    public static final BuildRecipe SCM = new BuildRecipe("scm.yaml");

    final String name;

    public BuildRecipe(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        BuildRecipe that = (BuildRecipe) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
