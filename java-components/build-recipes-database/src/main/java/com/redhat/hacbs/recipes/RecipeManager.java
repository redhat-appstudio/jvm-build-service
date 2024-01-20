package com.redhat.hacbs.recipes;

import java.io.IOException;
import java.nio.file.Path;

public interface RecipeManager<T> {

    T parse(Path file) throws IOException;

    void write(T data, Path file) throws IOException;
}
