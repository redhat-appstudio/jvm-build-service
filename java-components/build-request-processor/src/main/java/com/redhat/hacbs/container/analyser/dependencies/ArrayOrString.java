package com.redhat.hacbs.container.analyser.dependencies;

import java.util.Map;

public class ArrayOrString {
    String type = "string";
    String stringVal;
    String[] arrayVal;
    Map<String, String> objectVal;

    public String getType() {
        return type;
    }

    public ArrayOrString setType(String type) {
        this.type = type;
        return this;
    }

    public String getStringVal() {
        return stringVal;
    }

    public ArrayOrString setStringVal(String stringVal) {
        this.stringVal = stringVal;
        return this;
    }

    public String[] getArrayVal() {
        return arrayVal;
    }

    public ArrayOrString setArrayVal(String[] arrayVal) {
        this.arrayVal = arrayVal;
        return this;
    }

    public Map<String, String> getObjectVal() {
        return objectVal;
    }

    public ArrayOrString setObjectVal(Map<String, String> objectVal) {
        this.objectVal = objectVal;
        return this;
    }
}
