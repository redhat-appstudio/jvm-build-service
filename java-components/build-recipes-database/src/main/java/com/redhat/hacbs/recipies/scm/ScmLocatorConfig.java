package com.redhat.hacbs.recipies.scm;

import java.util.List;

public class ScmLocatorConfig {

    private static final String DEFAULT_RECIPE_REPO_URL = "https://github.com/redhat-appstudio/jvm-build-data";

    public static String getDefaultRecipeRepoUrl() {
        return DEFAULT_RECIPE_REPO_URL;
    }

    public static Builder builder() {
        return new ScmLocatorConfig().new Builder();
    }

    public class Builder {

        private boolean built;

        private Builder() {
        }

        private void ensureNotBuilt() {
            if (built) {
                throw new IllegalStateException("This builder instance has already been built");
            }
        }

        public ScmLocatorConfig build() {
            built = true;
            return ScmLocatorConfig.this;
        }

        public Builder setRecipeRepos(List<String> recipeRepos) {
            ensureNotBuilt();
            if (recipeRepos != null && !recipeRepos.isEmpty()) {
                ScmLocatorConfig.this.recipeRepos = recipeRepos;
            }
            return this;
        }

        public Builder setCacheUrl(String cacheUrl) {
            ensureNotBuilt();
            ScmLocatorConfig.this.cacheUrl = cacheUrl;
            return this;
        }
    }

    private List<String> recipeRepos = List.of(DEFAULT_RECIPE_REPO_URL);
    private String cacheUrl;

    private ScmLocatorConfig() {
    }

    public List<String> getRecipeRepos() {
        return recipeRepos;
    }

    public String getCacheUrl() {
        return cacheUrl;
    }
}
