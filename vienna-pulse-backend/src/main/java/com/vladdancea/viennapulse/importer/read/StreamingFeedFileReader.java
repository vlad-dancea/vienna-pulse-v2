package com.vladdancea.viennapulse.importer.read;

import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/// Reads one CSV file of a GTFS feed one record at a time, for files too large to hold in memory.
/// Unlike [FeedFileReader] it cannot see across rows, so checks such as unique ids belong to
/// whoever receives the records.
public interface StreamingFeedFileReader<T> {
    /// The file inside the feed, such as `trips.txt`.
    String file();

    /// Turns one row into a record, throwing [IllegalArgumentException] if it breaks a GTFS rule.
    T parse(NamedCsvRecord row);

    /// Passes records to `consumer` in file order; the caller keeps ownership of the ZIP. Fails with a
    /// [FeedFormatException] on the first bad row, naming its line.
    default void read(ZipFile feed, Consumer<? super T> consumer) throws IOException {
        FeedCsv.read(feed, file(), this::parse, (record, line) -> consumer.accept(record));
    }
}
