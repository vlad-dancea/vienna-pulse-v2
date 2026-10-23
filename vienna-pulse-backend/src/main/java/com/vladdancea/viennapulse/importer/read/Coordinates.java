package com.vladdancea.viennapulse.importer.read;

/// The range check for the latitudes and longitudes in `stops.txt` and `shapes.txt`.
public final class Coordinates {
    private Coordinates() {}

    /// Throws [IllegalArgumentException] unless `value` is between -90 and 90 degrees.
    public static void checkLatitude(String field, double value) {
        check(field, value, 90);
    }

    /// Throws [IllegalArgumentException] unless `value` is between -180 and 180 degrees.
    public static void checkLongitude(String field, double value) {
        check(field, value, 180);
    }

    private static void check(String field, double value, int limit) {
        if (!Double.isFinite(value) || value < -limit || value > limit) {
            throw new IllegalArgumentException(field + " " + value + " is not between " + -limit + " and " + limit);
        }
    }
}
