package com.redhat.hacbs.analyser.data.scm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Manages a Yaml list of repositories, and their processing state
 */
public class ScmManager implements AutoCloseable {

    public static final String PATH = "scm/scm-list.yaml";

    private final Path path;
    private final RepositoryData data;
    private final Map<String, Repository> byUri = new HashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    private ScmManager(Path path, RepositoryData data) {
        this.path = path;
        this.data = data;
        for (var i : data.getRepositories()) {
            byUri.put(i.getUri(), i);
        }
        Collections.sort(data.getRepositories());
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

    public List<Repository> getAll() {
        return Collections.unmodifiableList(data.getRepositories());
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
        writeData();
    }

    public void writeData() throws IOException {
        Files.createDirectories(path.getParent());
        Collections.sort(data.getRepositories());
        Files.writeString(path, mapper.writeValueAsString(data));
    }
}
