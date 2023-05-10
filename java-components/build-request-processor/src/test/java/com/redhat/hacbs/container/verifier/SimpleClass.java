package com.redhat.hacbs.container.verifier;

@Deprecated
@SuppressWarnings("unchecked")
public class SimpleClass {

    public int intField;

    private String stringField;

    @Deprecated
    public String publicMethod() {
        return "";
    }

    private String privateMethod() {
        return "";
    }
}
