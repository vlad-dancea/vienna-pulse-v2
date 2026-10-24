package com.vladdancea.viennapulse.importer.read;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/// Writes GTFS ZIPs for tests, from a single file or from the small feed in `src/test/resources/gtfs`.
public final class TestFeed {
    private static final List<String> FIXTURE_FILES = List.of(
            "agency.txt",
            "routes.txt",
            "stops.txt",
            "shapes.txt",
            "trips.txt",
            "stop_times.txt",
            "calendar.txt",
            "calendar_dates.txt");

    private TestFeed() {}

    public static ZipFile zip(Path dir, String file, String content) throws IOException {
        return new ZipFile(write(dir, file, content).toFile());
    }

    public static Path write(Path dir, String file, String content) throws IOException {
        return write(dir, Map.of(file, content));
    }

    public static Path write(Path dir, Map<String, String> files) throws IOException {
        var path = dir.resolve("feed.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return path;
    }

    /// The files of a feed with two lines that meet at Schwedenplatz, trimmed from the real feed,
    /// with its byte order marks and quotes. Tests change the returned map to break the feed.
    public static Map<String, String> fixture() {
        var files = new LinkedHashMap<String, String>();
        for (var file : FIXTURE_FILES) {
            try (var in = Objects.requireNonNull(TestFeed.class.getResourceAsStream("/gtfs/" + file), file)) {
                files.put(file, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return files;
    }
}
