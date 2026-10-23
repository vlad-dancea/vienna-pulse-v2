package com.vladdancea.viennapulse.importer.read;

import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.zip.ZipFile;

/// Reads one CSV file of a GTFS feed into one record per row.
public interface FeedFileReader<T> {
    /// The file inside the feed, such as `routes.txt`.
    String file();

    /// Turns one row into a record, throwing [IllegalArgumentException] if it breaks a GTFS rule.
    T parse(NamedCsvRecord row);

    /// The record's primary key, which must be unique within the file.
    String id(T record);

    /// Reads every record, keyed by id in file order. Fails with a [FeedFormatException] on the first
    /// bad row, naming its line.
    default SequencedMap<String, T> read(ZipFile feed) throws IOException {
        var records = new LinkedHashMap<String, T>();
        FeedCsv.read(feed, file(), this::parse, (record, line) -> {
            var id = id(record);
            if (records.putIfAbsent(id, record) != null) {
                throw FeedFormatException.at(file(), line, "duplicate id " + id);
            }
        });
        return Collections.unmodifiableSequencedMap(records);
    }
}
