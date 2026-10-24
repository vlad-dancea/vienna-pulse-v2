package com.vladdancea.viennapulse.importer.read.trip;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.optionalInteger;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.StreamingFeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.springframework.stereotype.Component;

/// Reads `trips.txt` one trip at a time. The feed has hundreds of thousands, too many to hold in
/// memory next to the rest of the import, so the database's primary key rejects a repeated id.
@Component
public class TripReader implements StreamingFeedFileReader<Trip> {
    @Override
    public String file() {
        return "trips.txt";
    }

    @Override
    public Trip parse(NamedCsvRecord row) {
        return new Trip(
                required(row, "trip_id"),
                required(row, "route_id"),
                required(row, "service_id"),
                optional(row, "shape_id"),
                optional(row, "trip_headsign"),
                optionalInteger(row, "direction_id"));
    }
}
