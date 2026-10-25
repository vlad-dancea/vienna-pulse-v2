package com.vladdancea.viennapulse.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vladdancea.viennapulse.api.geojson.Feature;
import com.vladdancea.viennapulse.api.geojson.Point;
import java.time.Instant;
import java.util.List;

/// The running vehicles as a GeoJSON feature collection, with the `time` their points are for as a
/// foreign member, which GeoJSON readers ignore. The seconds along each vehicle's path count from it.
@JsonPropertyOrder({"type", "time", "features"})
public record Vehicles(Instant time, List<Feature<Point, VehicleProperties>> features) {
    @JsonProperty
    public String type() {
        return "FeatureCollection";
    }
}
