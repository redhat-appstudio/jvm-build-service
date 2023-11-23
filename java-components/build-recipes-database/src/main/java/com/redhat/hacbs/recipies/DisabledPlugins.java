package com.redhat.hacbs.recipies;

import java.util.List;

public class DisabledPlugins {
    private List<String> disabledPlugins;

    public List<String> getDisabledPlugins() {
        return disabledPlugins;
    }

    public DisabledPlugins setDisabledPlugins(List<String> disabledPlugins) {
        this.disabledPlugins = disabledPlugins;
        return this;
    }
}
