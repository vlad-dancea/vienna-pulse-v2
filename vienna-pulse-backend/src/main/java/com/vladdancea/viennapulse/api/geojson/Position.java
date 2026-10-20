package com.vladdancea.viennapulse.api.geojson;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// A position within a line, written as `[longitude, latitude]` like the coordinates of a [Point].
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({"longitude", "latitude"})
public record Position(double longitude, double latitude) {}
