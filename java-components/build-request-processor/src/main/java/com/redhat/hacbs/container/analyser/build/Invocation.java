package com.redhat.hacbs.container.analyser.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Invocation {

    private List<String> commands = new ArrayList<>();
    private Map<String, String> toolVersion = new HashMap<>();

    private String jdkVersion;

    public Invocation() {

    }
    public Invocation(List<String> commands, Map<String, String> toolVersion, String jdkVersion) {
        this.commands = commands;
        this.toolVersion = toolVersion;
        this.jdkVersion = jdkVersion;
    }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Invocation that = (Invocation) o;
        return Objects.equals(commands, that.commands) && Objects.equals(toolVersion, that.toolVersion) && Objects.equals(jdkVersion, that.jdkVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commands, toolVersion, jdkVersion);
    }
}
