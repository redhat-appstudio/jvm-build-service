package com.redhat.hacbs.analyser.data.scm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages a Yaml list of repositories, and their processing state
 */
public class ScmManager implements AutoCloseable {

    public static final String PATH = "scm/scm-list.yaml";

    private final Path path;
    private final RepositoryData data;
    private final Map<String, Repository> byUri = new HashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    private ScmManager(Path path, RepositoryData data) {
        this.path = path;
        this.data = data;
        for (var i : data.getRepositories()) {
            byUri.put(i.getUri(), i);
        }
    }

    public static ScmManager create(Path repoRoot) {
        var path = repoRoot.resolve(PATH);
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                return new ScmManager(path, mapper.readValue(in, RepositoryData.class));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            return new ScmManager(path, new RepositoryData());
        }
    }

    public Repository get(String uri) {
        return byUri.get(uri);
    }

    public void add(Repository repository) {
        if (byUri.containsKey(repository.getUri())) {
            throw new IllegalStateException(repository.getUri() + " already exists");
        }
        byUri.put(repository.getUri(), repository);
        data.getRepositories().add(repository);
    }

    public Path getPath() {
        return path;
    }

    @Override
    public void close() throws Exception {
        Files.createDirectories(path.getParent());
        Collections.sort(data.getRepositories());
        Files.writeString(path, mapper.writeValueAsString(data));
    }
}
