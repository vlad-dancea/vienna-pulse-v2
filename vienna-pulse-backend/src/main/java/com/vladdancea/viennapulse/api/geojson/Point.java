package com.vladdancea.viennapulse.api.geojson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/// A position, written as `[longitude, latitude]`: GeoJSON puts longitude first, the reverse of
/// the usual spoken order.
@JsonPropertyOrder({"type", "coordinates"})
public record Point(
        @JsonIgnore double longitude, @JsonIgnore double latitude) implements Geometry {
    @JsonProperty
    public String type() {
        return "Point";
    }

    @JsonProperty
    public List<Double> coordinates() {
        return List.of(longitude, latitude);
    }
}
