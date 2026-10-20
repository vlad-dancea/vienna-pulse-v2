package com.vladdancea.viennapulse.importer.read.stopcall;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.StreamingFeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.springframework.stereotype.Component;

/// Streams stop membership from `stop_times.txt` without retaining its millions of call times.
@Component
public class StopCallReader implements StreamingFeedFileReader<StopCall> {
    @Override
    public String file() {
        return "stop_times.txt";
    }

    @Override
    public StopCall parse(NamedCsvRecord row) {
        var pickup = allowed(row, "pickup_type");
        var dropOff = allowed(row, "drop_off_type");
        return new StopCall(required(row, "trip_id"), required(row, "stop_id"), pickup || dropOff);
    }

    private static boolean allowed(NamedCsvRecord row, String field) {
        return switch (optional(row, field)) {
            case null -> true;
            case "0", "2", "3" -> true;
            case "1" -> false;
            default -> throw new IllegalArgumentException(field + " " + optional(row, field) + " is not 0 to 3");
        };
    }
}
