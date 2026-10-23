package com.vladdancea.viennapulse.importer.read.shape;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.decimal;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.integer;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.optionalDecimal;
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
        return new ShapePoint(
                required(row, "shape_id"),
                decimal(row, "shape_pt_lat"),
                decimal(row, "shape_pt_lon"),
                integer(row, "shape_pt_sequence"),
                optionalDecimal(row, "shape_dist_traveled"));
    }
}
