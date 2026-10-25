package com.vladdancea.viennapulse.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Where a vehicle is a number of seconds after the time of [Vehicles], written as
/// `[longitude, latitude, seconds]` like a GeoJSON position with the seconds in place of a height.
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({"longitude", "latitude", "seconds"})
public record Waypoint(double longitude, double latitude, double seconds) {}
