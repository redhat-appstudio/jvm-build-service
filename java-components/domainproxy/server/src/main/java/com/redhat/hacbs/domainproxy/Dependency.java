package com.redhat.hacbs.domainproxy;

import com.redhat.hacbs.resources.model.maven.GAV;

public record Dependency(GAV GAV, String classifier) {
}
