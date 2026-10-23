package com.vladdancea.viennapulse.importer.read.shape;

import com.vladdancea.viennapulse.importer.read.Coordinates;
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
        Coordinates.checkLatitude("shape_pt_lat", latitude);
        Coordinates.checkLongitude("shape_pt_lon", longitude);
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
}
