package com.vladdancea.viennapulse.importer.read;

import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/// Shared CSV rules for the files inside a GTFS ZIP, and the one parser for each field type, so the
/// same mistake reads the same in every file.
/// Wiener Linien writes UTF-8 with a byte order mark and quotes every field, and some names contain commas.
public final class FeedCsv {
    private static final Pattern DIGITS = Pattern.compile("[+-]?\\d+");

    private FeedCsv() {}

    // Closing the reader leaves the ZIP open.
    private static CsvReader<NamedCsvRecord> open(ZipFile feed, String file) throws IOException {
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

    /// Like [#required(NamedCsvRecord, String)], for a GTFS date such as `20261212`.
    public static LocalDate date(NamedCsvRecord row, String field) {
        var value = required(row, field);
        try {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " " + value + " is not a date in YYYYMMDD form", exception);
        }
    }

    /// Like [#required(NamedCsvRecord, String)], for a decimal number such as `48.20024`.
    public static double decimal(NamedCsvRecord row, String field) {
        return parseDecimal(field, required(row, field));
    }

    /// Like [#optional(NamedCsvRecord, String)], for a decimal number such as `48.20024`.
    public static @Nullable Double optionalDecimal(NamedCsvRecord row, String field) {
        var value = optional(row, field);
        return value == null ? null : parseDecimal(field, value);
    }

    /// Like [#required(NamedCsvRecord, String)], for a whole number that fits an `int`.
    public static int integer(NamedCsvRecord row, String field) {
        return parseInteger(field, required(row, field));
    }

    /// Like [#optional(NamedCsvRecord, String)], for a whole number that fits an `int`.
    public static @Nullable Integer optionalInteger(NamedCsvRecord row, String field) {
        var value = optional(row, field);
        return value == null ? null : parseInteger(field, value);
    }

    /// Parses every row of `file` and passes each record to `consumer` with the line its row starts
    /// on. A row that `parse` rejects with an [IllegalArgumentException] fails the read with a
    /// [FeedFormatException] naming that line. An [UncheckedIOException], from reading the file or
    /// from `consumer`, is unwrapped to its [IOException]. Other exceptions from `consumer` pass
    /// through unchanged.
    static <T> void read(
            ZipFile feed, String file, Function<NamedCsvRecord, T> parse, ObjLongConsumer<? super T> consumer)
            throws IOException {
        try (var csv = open(feed, file)) {
            for (var row : csv) {
                consumer.accept(parseLine(file, row, parse), row.getStartingLineNumber());
            }
        } catch (CsvParseException exception) {
            throw new FeedFormatException(file + " is not valid CSV", exception);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static <T> T parseLine(String file, NamedCsvRecord row, Function<NamedCsvRecord, T> parse) {
        try {
            return parse.apply(row);
        } catch (IllegalArgumentException exception) {
            throw FeedFormatException.at(file, row.getStartingLineNumber(), exception);
        }
    }

    private static double parseDecimal(String field, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " " + value + " is not a number", exception);
        }
    }

    private static int parseInteger(String field, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            var problem = DIGITS.matcher(value).matches() ? " is out of range" : " is not an integer";
            throw new IllegalArgumentException(field + " " + value + problem, exception);
        }
    }
}
