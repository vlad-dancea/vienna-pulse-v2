package com.vladdancea.viennapulse.importer.read.shape;

import org.jspecify.annotations.Nullable;

/// One point of a vehicle's path in `shapes.txt`, ordered within its shape by sequence.
public record ShapePoint(
        String shapeId,
        double latitude,
        double longitude,
        int sequence,
        @Nullable Double distanceTraveled) {

    public ShapePoint {
        if (shapeId.isBlank()) {
            throw new IllegalArgumentException("shape id is blank");
        }
        validateCoordinate("shape_pt_lat", latitude, 90);
        validateCoordinate("shape_pt_lon", longitude, 180);
        if (sequence < 0) {
            throw new IllegalArgumentException("shape_pt_sequence " + sequence + " is negative");
        }
        if (distanceTraveled != null) {
            if (!Double.isFinite(distanceTraveled)) {
                throw new IllegalArgumentException("shape_dist_traveled " + distanceTraveled + " is not finite");
            }
            if (distanceTraveled < 0) {
                throw new IllegalArgumentException("shape_dist_traveled " + distanceTraveled + " is negative");
            }
        }
    }

    private static void validateCoordinate(String field, double value, int limit) {
        if (!Double.isFinite(value) || value < -limit || value > limit) {
            throw new IllegalArgumentException(field + " " + value + " is not between " + -limit + " and " + limit);
        }
    }
}
