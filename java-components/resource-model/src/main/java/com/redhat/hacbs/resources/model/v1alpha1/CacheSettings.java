package com.redhat.hacbs.resources.model.v1alpha1;

public class CacheSettings {
    private String requestMemory;
    private String requestCPU;
    private String limitMemory;
    private String limitCPwU;
    private String iOThreads;
    private String workerThreads;
    private String storage;
    private boolean disableTLS;

    public String getRequestMemory() {
        return requestMemory;
    }

    public CacheSettings setRequestMemory(String requestMemory) {
        this.requestMemory = requestMemory;
        return this;
    }

    public String getRequestCPU() {
        return requestCPU;
    }

    public CacheSettings setRequestCPU(String requestCPU) {
        this.requestCPU = requestCPU;
        return this;
    }

    public String getLimitMemory() {
        return limitMemory;
    }

    public CacheSettings setLimitMemory(String limitMemory) {
        this.limitMemory = limitMemory;
        return this;
    }

    public String getLimitCPwU() {
        return limitCPwU;
    }

    public CacheSettings setLimitCPwU(String limitCPwU) {
        this.limitCPwU = limitCPwU;
        return this;
    }

    public String getiOThreads() {
        return iOThreads;
    }

    public CacheSettings setiOThreads(String iOThreads) {
        this.iOThreads = iOThreads;
        return this;
    }

    public String getWorkerThreads() {
        return workerThreads;
    }

    public CacheSettings setWorkerThreads(String workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public String getStorage() {
        return storage;
    }

    public CacheSettings setStorage(String storage) {
        this.storage = storage;
        return this;
    }

    public boolean isDisableTLS() {
        return disableTLS;
    }

    public CacheSettings setDisableTLS(boolean disableTLS) {
        this.disableTLS = disableTLS;
        return this;
    }
}
