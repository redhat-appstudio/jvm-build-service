package com.redhat.hacbs.recipies.scm;

public class TagMapping {

    /**
     * A regex that is matches against a repository tag
     */
    private String tagPattern;
    /**
     * The corresponding version string, it can use <code>{x}</code> to
     * reference match groups from the tag pattern.
     */
    private String version;

    public String getTagPattern() {
        return tagPattern;
    }

    public TagMapping setTagPattern(String tagPattern) {
        this.tagPattern = tagPattern;
        return this;
    }

    public String getVersion() {
        return version;
    }

    public TagMapping setVersion(String version) {
        this.version = version;
        return this;
    }
}
