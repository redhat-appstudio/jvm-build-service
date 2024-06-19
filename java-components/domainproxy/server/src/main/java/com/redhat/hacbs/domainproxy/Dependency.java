package com.redhat.hacbs.domainproxy;

import com.redhat.hacbs.common.sbom.GAV;

public record Dependency(GAV GAV, String classifier) {
}
