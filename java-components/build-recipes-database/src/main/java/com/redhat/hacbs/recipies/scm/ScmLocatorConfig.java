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

        /**
         * A list of build recipe repo URIs
         *
         * @param recipeRepos recipe repo URIs
         * @return this builder instance
         */
        public Builder setRecipeRepos(List<String> recipeRepos) {
            ensureNotBuilt();
            if (recipeRepos != null && !recipeRepos.isEmpty()) {
                ScmLocatorConfig.this.recipeRepos = recipeRepos;
            }
            return this;
        }

        /**
         * Whether to cache code repository tags between {@link ScmLocator.resolveTagInfo(GAV)} calls
         *
         * @param cacheRepoTags whether to cache code repository tags
         * @return this builder instance
         */
        public Builder setCacheRepoTags(boolean cacheRepoTags) {
            ensureNotBuilt();
            ScmLocatorConfig.this.cacheRepoTags = cacheRepoTags;
            return this;
        }

        public Builder setCacheUrl(String cacheUrl) {
            ensureNotBuilt();
            ScmLocatorConfig.this.cacheUrl = cacheUrl;
            return this;
        }
    }

    private List<String> recipeRepos = List.of(DEFAULT_RECIPE_REPO_URL);
    private boolean cacheRepoTags;
    private String cacheUrl;

    private ScmLocatorConfig() {
    }

    public List<String> getRecipeRepos() {
        return recipeRepos;
    }

    public boolean isCacheRepoTags() {
        return cacheRepoTags;
    }

    public String getCacheUrl() {
        return cacheUrl;
    }
}
