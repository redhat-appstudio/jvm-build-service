package com.redhat.hacbs.container.analyser.dependencies;

public class TaskRunResult {

    private String name;
    private String value;

    private String type;

    public TaskRunResult() {
    }

    public TaskRunResult(String name, String value) {
        this.name = name;
        this.value = value;
        this.type = "string";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public TaskRunResult setType(String type) {
        this.type = type;
        return this;
    }
}
