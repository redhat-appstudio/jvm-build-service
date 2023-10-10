package com.redhat.hacbs.container.analyser.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Invocation {

    private List<String> commands = new ArrayList<>();
    private Map<String, String> toolVersion = new HashMap<>();

    public List<String> getCommands() {
        return commands;
    }

    public Invocation setCommands(List<String> commands) {
        this.commands = commands;
        return this;
    }

    public Map<String, String> getToolVersion() {
        return toolVersion;
    }

    public Invocation setToolVersion(Map<String, String> toolVersion) {
        this.toolVersion = toolVersion;
        return this;
    }

    @Override
    public String toString() {
        return "Invocation{" +
            "commands=" + commands +
            ", toolVersion=" + toolVersion +
            '}';
    }
}
