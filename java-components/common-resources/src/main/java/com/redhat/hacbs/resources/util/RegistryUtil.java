package com.redhat.hacbs.resources.util;

import com.redhat.hacbs.resources.model.v1alpha1.jbsconfigstatus.ImageRegistry;

public class RegistryUtil {
    public static ImageRegistry parseRegistry(String registry) {
        ImageRegistry result = new ImageRegistry();
        // This represents a comma-separated sequence in the *same* order as defined in
        // ImageRegistry in pkg/apis/jvmbuildservice/v1alpha1/jbsconfig_types.go
        String[] splitRegistry = registry.split(",", -1);
        if (splitRegistry.length != 6) {
            throw new RuntimeException("Invalid registry format");
        }
        result.setHost(splitRegistry[0]);
        result.setPort(splitRegistry[1]);
        result.setOwner(splitRegistry[2]);
        result.setRepository(splitRegistry[3]);
        result.setInsecure(Boolean.parseBoolean(splitRegistry[4]));
        result.setPrependTag(splitRegistry[5]);

        return result;
    }
}
