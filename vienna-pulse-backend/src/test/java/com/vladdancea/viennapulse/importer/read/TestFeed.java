package com.vladdancea.viennapulse.importer.read;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/// Writes a GTFS ZIP with a single file, for reader tests.
public final class TestFeed {
    private TestFeed() {}

    public static ZipFile zip(Path dir, String file, String content) throws IOException {
        return new ZipFile(write(dir, file, content).toFile());
    }

    public static Path write(Path dir, String file, String content) throws IOException {
        var path = dir.resolve("feed.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(file));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return path;
    }
}
