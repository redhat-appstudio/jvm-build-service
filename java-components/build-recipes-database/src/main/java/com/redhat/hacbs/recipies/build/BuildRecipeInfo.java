package com.redhat.hacbs.recipies.build;

import java.util.ArrayList;
import java.util.List;

/**
 * TODO: this should be stored per repo/tag/path, not per artifact
 * otherwise in theory artifacts could have different settings which would result in a non-deterministic outcome
 * This is not a now problem, but something we should address in the mid term.
 *
 */
public class BuildRecipeInfo {

    public List<String> additionalArgs = new ArrayList<>();

    public List<String> getAdditionalArgs() {
        return additionalArgs;
    }

    public BuildRecipeInfo setAdditionalArgs(List<String> additionalArgs) {
        this.additionalArgs = additionalArgs;
        return this;
    }
}
