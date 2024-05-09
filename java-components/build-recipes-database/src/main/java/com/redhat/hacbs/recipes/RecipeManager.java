package com.redhat.hacbs.recipes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public interface RecipeManager<T> {

    default T parse(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return parse(in);
        }
    }

    T parse(InputStream in) throws IOException;

    void write(T data, OutputStream out) throws IOException;

    default void write(T data, Path file) throws IOException {
        try (var out = Files.newOutputStream(file)) {
            write(data, out);
        }
    }
}
