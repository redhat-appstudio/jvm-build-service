package com.redhat.hacbs.container.hermetic;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.inject.Singleton;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import io.quarkus.arc.Unremovable;
import picocli.CommandLine;

@CommandLine.Command(name = "download-dependency-list")
@Singleton
@Unremovable
public class DownloadDependencies implements Runnable {

    final int THREADS = 6;

    @CommandLine.Option(names = "--dep-file", description = "The dependencies file", required = true)
    Path dependencies;

    @CommandLine.Option(names = "--m2-dir", required = true, description = "The path to the local repo")
    Path repo;

    @CommandLine.Option(names = "--url", required = true, description = "The URL to download from")
    List<String> uris;

    @Override
    public void run() {
        var start = Instant.now();
        List<String> baseUris = new ArrayList<>();
        for (var uri : uris) {
            String baseUri = uri.endsWith("/") ? uri : (uri + "/");
            baseUris.add(baseUri);
        }
        List<Future<Void>> results = new ArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(THREADS);
        try {

            try (CloseableHttpClient client = HttpClientBuilder.create().setMaxConnPerRoute(THREADS).build()) {
                var deps = Files.readAllLines(dependencies);
                for (var i : deps) {
                    var parts = i.split(":");
                    var group = parts[0].replaceAll("\\.", "/");
                    var artifact = parts[1];
                    var version = parts[2];
                    var classifier = parts[3];
                    var type = parts[4];
                    Path parent = repo.resolve(group).resolve(artifact).resolve(version);
                    Files.createDirectories(parent);
                    String fileName = artifact + "-" + version + (classifier.isBlank() ? "" : ("-" + classifier)) + "." + type;
                    Path target = parent.resolve(fileName);
                    List<URI> turis = new ArrayList<>();
                    for (var baseUri: baseUris) {
                        URI turi = new URI(baseUri + group + "/" + artifact + "/" + version + "/" + fileName);
                        turis.add(turi);
                    }
                    results.add(executorService.submit(new Callable<Void>() {
                        @Override
                        public Void call() throws Exception {
                            Exception last = null;
                            for (var i : turis) {
                                try {
                                    var result = client.execute(new HttpGet(i));
                                    if (result.getStatusLine().getStatusCode() != 200) {
                                        throw new RuntimeException("wrong status " + result.getStatusLine().getStatusCode());
                                    }
                                    try (var out = Files.newOutputStream(target)) {
                                        result.getEntity().writeTo(out);
                                    }
                                    return null;
                                } catch (Exception e) {
                                    last = e;
                                }
                            }
                            throw new RuntimeException(last);
                        }
                    }));
                }
                for (var i : results) {
                    i.get();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            executorService.shutdown();
            System.out.println("Download took " + (Instant.now().getEpochSecond() - start.getEpochSecond()) + "s");
        }
    }

}
