package com.vladdancea.viennapulse.importer.zip;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/// Checks that a downloaded feed contains every file our importer needs.
@Component
public class FeedValidator {
    private static final List<String> REQUIRED_FILES = List.of(
            "agency.txt",
            "routes.txt",
            "trips.txt",
            "stops.txt",
            "stop_times.txt",
            "shapes.txt",
            "calendar.txt",
            "calendar_dates.txt");

    /// Throws [ZipException] naming the first required file that is missing or empty.
    public void validate(Path file) throws IOException {
        try (var zip = new ZipFile(file.toFile())) {
            for (var name : REQUIRED_FILES) {
                var entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory() || entry.getSize() <= 0) {
                    throw new ZipException("GTFS ZIP is missing a file: " + name);
                }
            }
        }
    }
}
