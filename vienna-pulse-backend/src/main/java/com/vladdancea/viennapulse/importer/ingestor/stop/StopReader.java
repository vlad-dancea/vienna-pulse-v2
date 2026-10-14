package com.vladdancea.viennapulse.importer.ingestor.stop;

import static com.vladdancea.viennapulse.importer.ingestor.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.ingestor.FeedCsv.required;

import com.vladdancea.viennapulse.importer.ingestor.FeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.springframework.stereotype.Component;

/// Reads `stops.txt` into [Stop] records. Only plain stops are supported: Wiener Linien publishes
/// no stations, entrances or boarding areas, so any other `location_type` is rejected.
@Component
public class StopReader implements FeedFileReader<Stop> {
    @Override
    public String file() {
        return "stops.txt";
    }

    @Override
    public Stop parse(NamedCsvRecord row) {
        var locationType = optional(row, "location_type");
        if (locationType != null && !locationType.equals("0")) {
            throw new IllegalArgumentException("unsupported location type " + locationType);
        }
        return new Stop(
                required(row, "stop_id"),
                required(row, "stop_name"),
                coordinate(row, "stop_lat"),
                coordinate(row, "stop_lon"),
                optional(row, "zone_id"));
    }

    @Override
    public String id(Stop stop) {
        return stop.id();
    }

    private static double coordinate(NamedCsvRecord row, String field) {
        var value = required(row, field);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " " + value + " is not a number", exception);
        }
    }
}
