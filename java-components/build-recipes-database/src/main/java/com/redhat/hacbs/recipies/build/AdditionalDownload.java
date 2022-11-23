package com.redhat.hacbs.recipies.build;

public class AdditionalDownload {

    private String uri;
    private String sha256;

    /**
     * only applies to executable files, the name of the resulting executable
     */
    private String fileName;

    private String binaryPath;

    /**
     * Possible values:
     *
     * executable
     * tar
     *
     */
    private String type;

    public String getUri() {
        return uri;
    }

    public AdditionalDownload setUri(String uri) {
        this.uri = uri;
        return this;
    }

    public String getSha256() {
        return sha256;
    }

    public AdditionalDownload setSha256(String sha256) {
        this.sha256 = sha256;
        return this;
    }

    public String getBinaryPath() {
        return binaryPath;
    }

    public AdditionalDownload setBinaryPath(String binaryPath) {
        this.binaryPath = binaryPath;
        return this;
    }

    public String getFileName() {
        return fileName;
    }

    public AdditionalDownload setFileName(String fileName) {
        this.fileName = fileName;
        return this;
    }

    public String getType() {
        return type;
    }

    public AdditionalDownload setType(String type) {
        this.type = type;
        return this;
    }
}
