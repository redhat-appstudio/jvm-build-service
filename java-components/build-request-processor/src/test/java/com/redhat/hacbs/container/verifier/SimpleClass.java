package com.redhat.hacbs.container.verifier;

@Deprecated
@SuppressWarnings("unchecked")
public class SimpleClass {

    public int intField;

    @Deprecated
    public String publicMethod() {
        return "";
    }

    private String privateMethod() {
        return "";
    }
}
