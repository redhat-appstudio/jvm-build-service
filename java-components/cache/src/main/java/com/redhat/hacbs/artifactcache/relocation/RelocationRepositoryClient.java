package com.redhat.hacbs.artifactcache.relocation;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.ws.rs.NotFoundException;

import com.redhat.hacbs.artifactcache.services.ArtifactResult;
import com.redhat.hacbs.artifactcache.services.RepositoryClient;
import com.redhat.hacbs.resources.model.maven.GAV;
import com.redhat.hacbs.resources.util.HashUtil;

public class RelocationRepositoryClient implements RepositoryClient {
    final Map<Pattern, String> gavRelocations;

    public RelocationRepositoryClient(Map<String, String> relocations) {
        this.gavRelocations = new HashMap<>();
        for (var i : relocations.entrySet()) {
            gavRelocations.put(Pattern.compile(i.getKey()), i.getValue());
        }
    }

    @Override
    public String getName() {
        return "relocations";
    }

    @Override
    public Optional<ArtifactResult> getArtifactFile(String group, String artifact, String version, String target) {
        if (isRelocation(group, artifact, version)) {
            if (target.endsWith(DOT_POM)) {
                return getGavRelocation(group, artifact, version);
            } else if (target.endsWith(DOT_POM_DOT_SHA1)) {
                return getGavRelocationSha1(group, artifact, version);
            } else {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ArtifactResult> getMetadataFile(String group, String target) {
        return Optional.empty();
    }

    private Optional<ArtifactResult> getGavRelocation(String group, String artifact, String version) {
        byte[] b = getGavRelocationBytes(group, artifact, version);
        return Optional.of(new ArtifactResult(null, new ByteArrayInputStream(b), b.length, Optional.of(HashUtil.sha1(b)),
                Collections.emptyMap()));
    }

    private Optional<ArtifactResult> getGavRelocationSha1(String group, String artifact, String version) {
        var res = getGavRelocation(group, artifact, version);
        if (res.isPresent() && res.get().getExpectedSha().isPresent()) {
            byte[] bytes = res.get().getExpectedSha().get().getBytes(StandardCharsets.UTF_8);
            return Optional.of(new ArtifactResult(null, new ByteArrayInputStream(bytes), bytes.length, Optional.empty(),
                    Collections.emptyMap()));
        }
        return Optional.empty();
    }

    private byte[] getGavRelocationBytes(String group, String artifact, String version) {
        group = toGroupId(group);
        String fromKey = toGavKey(group, artifact, version);
        Map.Entry<Pattern, String> match = findFirstMatch(fromKey).get();

        Pattern pattern = match.getKey();
        Matcher matcher = pattern.matcher(fromKey);
        String toKey = match.getValue();

        if (matcher.matches()) {
            for (int i = 1; i <= matcher.groupCount(); ++i) {
                toKey = toKey.replaceAll(DOLLAR_VAR + i, matcher.group(i));
            }
            return RelocationCreator.create(new GAV(group, artifact, version), toGav(toKey));
        }
        throw new NotFoundException();
    }

    private boolean isRelocation(String group, String artifact, String version) {
        if (!gavRelocations.isEmpty()) {
            group = toGroupId(group);
            String key = toGavKey(group, artifact, version);

            Optional<Map.Entry<Pattern, String>> match = findFirstMatch(key);

            return match.isPresent();
        }
        return false;
    }

    private Optional<Map.Entry<Pattern, String>> findFirstMatch(String key) {
        return gavRelocations.entrySet()
                .stream()
                .filter(s -> s.getKey().matcher(key).matches())
                .findFirst();
    }

    private String toGroupId(String group) {
        return group.replaceAll(SLASH, DOT);
    }

    private String toGavKey(String group, String artifact, String version) {
        return String.format(GAV_PATTERN, group, artifact, version);
    }

    private GAV toGav(String key) {
        if (key.contains(DOUBLE_POINT)) {
            String[] parts = key.split(DOUBLE_POINT);
            if (parts.length == 3) {
                return new GAV(parts[0], parts[1], parts[2]);
            }
        }
        throw new RuntimeException("Invalid mapping for [" + key + "].");
    }

    private static final String GAV_PATTERN = "%s:%s:%s";
    private static final String DOUBLE_POINT = ":";
    private static final String DOLLAR_VAR = "\\$";
    private static final String SLASH = "/";
    private static final String DOT = ".";
    private static final String DOT_SHA1 = ".sha1";
    private static final String DOT_POM = ".pom";
    private static final String DOT_POM_DOT_SHA1 = DOT_POM + DOT_SHA1;
}
