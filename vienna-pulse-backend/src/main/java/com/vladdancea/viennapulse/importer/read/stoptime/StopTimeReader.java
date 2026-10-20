package com.vladdancea.viennapulse.importer.read.stoptime;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.StreamingFeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/// Reads `stop_times.txt` one call at a time. The feed has millions, far too many to hold in memory,
/// so the database's primary key rejects a stop sequence used twice within a trip.
@Component
public class StopTimeReader implements StreamingFeedFileReader<StopTime> {
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):([0-5]\\d):([0-5]\\d)");

    @Override
    public String file() {
        return "stop_times.txt";
    }

    @Override
    public StopTime parse(NamedCsvRecord row) {
        var distance = optional(row, "shape_dist_traveled");
        return new StopTime(
                required(row, "trip_id"),
                sequence(required(row, "stop_sequence")),
                required(row, "stop_id"),
                time("arrival_time", optional(row, "arrival_time")),
                time("departure_time", optional(row, "departure_time")),
                type(row, "pickup_type"),
                type(row, "drop_off_type"),
                distance == null ? null : distance(distance));
    }

    private static int sequence(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("stop_sequence " + value + " is not an integer", exception);
        }
    }

    // GTFS writes HH:MM:SS, or H:MM:SS before 10 o'clock, and lets hours pass 24 after midnight.
    private static @Nullable Integer time(String field, @Nullable String value) {
        if (value == null) {
            return null;
        }
        var matcher = TIME.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(field + " " + value + " is not a time in HH:MM:SS form");
        }
        return Integer.parseInt(matcher.group(1)) * 3600
                + Integer.parseInt(matcher.group(2)) * 60
                + Integer.parseInt(matcher.group(3));
    }

    private static PickupDropOffType type(NamedCsvRecord row, String field) {
        var code = optional(row, field);
        if (code == null) {
            return PickupDropOffType.REGULAR;
        }
        return PickupDropOffType.fromCode(code)
                .orElseThrow(() -> new IllegalArgumentException(field + " " + code + " is not 0 to 3"));
    }

    private static double distance(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("shape_dist_traveled " + value + " is not a number", exception);
        }
    }
}
