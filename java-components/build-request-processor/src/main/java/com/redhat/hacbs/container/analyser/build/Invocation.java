package com.redhat.hacbs.container.analyser.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Invocation {

    private List<String> commands = new ArrayList<>();
    private Map<String, String> toolVersion = new HashMap<>();

    private String jdkVersion;

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

    public String getJdkVersion() {
        return jdkVersion;
    }

    public Invocation setJdkVersion(String jdkVersion) {
        this.jdkVersion = jdkVersion;
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
