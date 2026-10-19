package com.vladdancea.viennapulse.importer.read.shape;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.FeedCsv;
import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/// Reads `shapes.txt` one point at a time without retaining the whole file in memory.
/// A shape's rows need not be adjacent or sorted, so checks across rows, such as unique sequence
/// numbers and growing distances, belong to whoever groups the points.
@Component
public class ShapeReader {
    private static final String FILE = "shapes.txt";

    /// Passes points to `consumer` in file order; the caller keeps ownership of the ZIP.
    public void read(ZipFile feed, Consumer<? super ShapePoint> consumer) throws IOException {
        try (var csv = FeedCsv.open(feed, FILE)) {
            for (var row : csv) {
                consumer.accept(parseLine(row));
            }
        } catch (CsvParseException exception) {
            throw new FeedFormatException(FILE + " is not valid CSV", exception);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static ShapePoint parseLine(NamedCsvRecord row) {
        try {
            var distance = optional(row, "shape_dist_traveled");
            return new ShapePoint(
                    required(row, "shape_id"),
                    number("shape_pt_lat", required(row, "shape_pt_lat")),
                    number("shape_pt_lon", required(row, "shape_pt_lon")),
                    sequence(required(row, "shape_pt_sequence")),
                    distance == null ? null : number("shape_dist_traveled", distance));
        } catch (IllegalArgumentException exception) {
            throw FeedFormatException.at(FILE, row.getStartingLineNumber(), exception);
        }
    }

    private static double number(String field, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " " + value + " is not a number", exception);
        }
    }

    private static int sequence(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            var problem = value.matches("[+-]?\\d+") ? " is out of range" : " is not an integer";
            throw new IllegalArgumentException("shape_pt_sequence " + value + problem, exception);
        }
    }
}
