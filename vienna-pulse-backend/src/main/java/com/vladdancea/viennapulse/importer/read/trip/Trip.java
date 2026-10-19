package com.vladdancea.viennapulse.importer.read.trip;

import org.jspecify.annotations.Nullable;

/// One row of `trips.txt`: a single journey of a route, run on the days of its service, along its
/// shape. The constructor rejects a row that breaks the GTFS rules with an [IllegalArgumentException].
public record Trip(
        String id,
        String routeId,
        String serviceId,
        @Nullable String shapeId,
        @Nullable String headsign,
        @Nullable Integer directionId) {

    public Trip {
        if (id.isBlank()) {
            throw new IllegalArgumentException("trip id is blank");
        }
        if (routeId.isBlank()) {
            throw new IllegalArgumentException("trip " + id + " has a blank route id");
        }
        if (serviceId.isBlank()) {
            throw new IllegalArgumentException("trip " + id + " has a blank service id");
        }
        // GTFS gives the two directions no meaning beyond being opposite each other.
        if (directionId != null && directionId != 0 && directionId != 1) {
            throw new IllegalArgumentException("direction_id " + directionId + " is not 0 or 1");
        }
    }
}
