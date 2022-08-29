package com.redhat.hacbs.container.analyser.build;

import java.util.Map;

import com.redhat.hacbs.container.analyser.location.VersionRange;

public class DiscoveryResult implements Comparable<DiscoveryResult> {

    final Map<String, VersionRange> toolVersions;

    final int priority;

    public DiscoveryResult(Map<String, VersionRange> toolVersions, int priority) {
        this.toolVersions = toolVersions;
        this.priority = priority;
    }

    public Map<String, VersionRange> getToolVersions() {
        return toolVersions;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public int compareTo(DiscoveryResult o) {
        return Integer.compare(priority, o.priority);
    }
}
