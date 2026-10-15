package com.vladdancea.viennapulse.importer.read;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/// Shared CSV rules for the files inside a GTFS ZIP.
/// Wiener Linien writes UTF-8 with a byte order mark and quotes every field, and some names contain commas.
public final class FeedCsv {
    private FeedCsv() {}

    static CsvReader<NamedCsvRecord> open(ZipFile feed, String file) throws IOException {
        var entry = feed.getEntry(file);
        if (entry == null) {
            throw new FeedFormatException("GTFS feed is missing " + file);
        }
        return CsvReader.builder()
                .detectBomHeader(true)
                .ofNamedCsvRecord(feed.getInputStream(entry), StandardCharsets.UTF_8);
    }

    /// The field without surrounding whitespace, or `null` if the column is missing or the field is empty.
    /// GTFS treats both the same way.
    public static @Nullable String optional(NamedCsvRecord row, String field) {
        return row.findField(field)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .orElse(null);
    }

    /// Like [#optional(NamedCsvRecord, String)],
    /// but throws [IllegalArgumentException] when there is no value.
    public static String required(NamedCsvRecord row, String field) {
        var value = optional(row, field);
        if (value == null) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value;
    }
}
