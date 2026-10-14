package com.vladdancea.viennapulse.importer.zip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// A downloaded feed in a temporary file, which [#close()] deletes.
public record DownloadedFeed(Path path) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
