package com.vladdancea.viennapulse.importer.ingestor.stop;

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
        validateCoordinate("stop_lat", latitude, 90);
        validateCoordinate("stop_lon", longitude, 180);
    }

    private static void validateCoordinate(String field, double value, int limit) {
        if (!Double.isFinite(value) || value < -limit || value > limit) {
            throw new IllegalArgumentException(field + " " + value + " is not between " + -limit + " and " + limit);
        }
    }
}
