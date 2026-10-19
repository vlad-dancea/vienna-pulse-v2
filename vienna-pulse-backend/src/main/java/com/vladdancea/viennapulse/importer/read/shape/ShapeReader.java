package com.vladdancea.viennapulse.importer.read.shape;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.StreamingFeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.springframework.stereotype.Component;

/// Reads `shapes.txt` one point at a time without retaining the whole file in memory.
/// A shape's rows need not be adjacent or sorted, so checks across rows, such as unique sequence
/// numbers and growing distances, belong to whoever groups the points.
@Component
public class ShapeReader implements StreamingFeedFileReader<ShapePoint> {
    @Override
    public String file() {
        return "shapes.txt";
    }

    @Override
    public ShapePoint parse(NamedCsvRecord row) {
        var distance = optional(row, "shape_dist_traveled");
        return new ShapePoint(
                required(row, "shape_id"),
                number("shape_pt_lat", required(row, "shape_pt_lat")),
                number("shape_pt_lon", required(row, "shape_pt_lon")),
                sequence(required(row, "shape_pt_sequence")),
                distance == null ? null : number("shape_dist_traveled", distance));
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
