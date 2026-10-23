package com.vladdancea.viennapulse.importer.read.stop;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.decimal;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.FeedFileReader;
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
                decimal(row, "stop_lat"),
                decimal(row, "stop_lon"),
                optional(row, "zone_id"));
    }

    @Override
    public String id(Stop stop) {
        return stop.id();
    }
}
