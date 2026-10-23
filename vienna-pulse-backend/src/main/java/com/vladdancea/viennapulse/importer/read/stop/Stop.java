package com.vladdancea.viennapulse.importer.read.stop;

import com.vladdancea.viennapulse.importer.read.Coordinates;
import org.jspecify.annotations.Nullable;

/// One row of `stops.txt`. The constructor rejects a row that breaks the GTFS rules with an
/// [IllegalArgumentException].
public record Stop(
        String id,
        String name,
        double latitude,
        double longitude,
        @Nullable String zoneId) {

    public Stop {
        if (id.isBlank()) {
            throw new IllegalArgumentException("stop id is blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("stop " + id + " has a blank name");
        }
        Coordinates.checkLatitude("stop_lat", latitude);
        Coordinates.checkLongitude("stop_lon", longitude);
    }
}
