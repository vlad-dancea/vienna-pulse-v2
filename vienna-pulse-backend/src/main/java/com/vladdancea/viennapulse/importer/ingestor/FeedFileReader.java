package com.vladdancea.viennapulse.importer.ingestor;

import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.zip.ZipFile;

/** Reads one CSV file of a GTFS feed into one record per row. */
public interface FeedFileReader<T> {
    /** The file inside the feed, such as {@code routes.txt}. */
    String file();

    /** Turns one row into a record, throwing {@link IllegalArgumentException} if it breaks a GTFS rule. */
    T parse(NamedCsvRecord row);

    /** The record's primary key, which must be unique within the file. */
    String id(T record);

    /** Reads every record, keyed by id in file order. Fails on the first bad row, naming its line. */
    default SequencedMap<String, T> read(ZipFile feed) throws IOException {
        var records = new LinkedHashMap<String, T>();
        try (var csv = FeedCsv.open(feed, file())) {
            for (var row : csv) {
                var record = parseLine(row);
                var id = id(record);
                if (records.putIfAbsent(id, record) != null) {
                    throw FeedFormatException.at(file(), row.getStartingLineNumber(), "duplicate id " + id);
                }
            }
        } catch (CsvParseException exception) {
            throw new FeedFormatException(file() + " is not valid CSV", exception);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        return Collections.unmodifiableSequencedMap(records);
    }

    private T parseLine(NamedCsvRecord row) {
        try {
            return parse(row);
        } catch (IllegalArgumentException exception) {
            throw FeedFormatException.at(file(), row.getStartingLineNumber(), exception);
        }
    }
}
