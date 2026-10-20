package com.vladdancea.viennapulse.importer.read.stopcall;

/// A trip's call at a platform, eligible for mode membership when passengers can board or alight.
public record StopCall(String tripId, String stopId, boolean eligible) {
    public StopCall {
        if (tripId.isBlank() || stopId.isBlank()) {
            throw new IllegalArgumentException("stop call has a blank trip or stop id");
        }
    }
}
