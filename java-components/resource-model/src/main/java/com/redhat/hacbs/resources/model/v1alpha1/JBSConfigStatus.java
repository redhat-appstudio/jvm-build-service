package com.redhat.hacbs.resources.model.v1alpha1;

public class JBSConfigStatus {

    private ImageRegistry imageRegistry;
    private boolean rebuildsPossible;
    private String message;

    public ImageRegistry getImageRegistry() {
        return imageRegistry;
    }

    public JBSConfigStatus setImageRegistry(ImageRegistry imageRegistry) {
        this.imageRegistry = imageRegistry;
        return this;
    }

    public boolean isRebuildsPossible() {
        return rebuildsPossible;
    }

    public JBSConfigStatus setRebuildsPossible(boolean rebuildsPossible) {
        this.rebuildsPossible = rebuildsPossible;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public JBSConfigStatus setMessage(String message) {
        this.message = message;
        return this;
    }
}
