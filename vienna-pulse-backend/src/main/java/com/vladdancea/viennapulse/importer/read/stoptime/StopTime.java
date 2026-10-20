package com.vladdancea.viennapulse.importer.read.stoptime;

import org.jspecify.annotations.Nullable;

/// One row of `stop_times.txt`: a trip's call at a platform. Times are seconds after noon minus
/// 12 hours on the service day, which is midnight except on daylight saving days, and pass 24 hours
/// for trips that run after midnight. The constructor rejects a row that breaks the GTFS rules with
/// an [IllegalArgumentException].
public record StopTime(
        String tripId,
        int sequence,
        String stopId,
        @Nullable Integer arrivalTime,
        @Nullable Integer departureTime,
        PickupDropOffType pickupType,
        PickupDropOffType dropOffType,
        @Nullable Double distanceTraveled) {

    public StopTime {
        if (tripId.isBlank() || stopId.isBlank()) {
            throw new IllegalArgumentException("stop time has a blank trip or stop id");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("stop_sequence " + sequence + " is negative");
        }
        if (arrivalTime != null && departureTime != null && arrivalTime > departureTime) {
            throw new IllegalArgumentException("arrival_time is after departure_time");
        }
        if (distanceTraveled != null && (!Double.isFinite(distanceTraveled) || distanceTraveled < 0)) {
            throw new IllegalArgumentException("shape_dist_traveled " + distanceTraveled + " is not a finite distance");
        }
    }
}
