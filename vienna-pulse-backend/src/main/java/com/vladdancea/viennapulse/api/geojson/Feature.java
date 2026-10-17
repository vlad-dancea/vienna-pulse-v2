package com.vladdancea.viennapulse.api.geojson;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One thing on the map: where it is, and what to show about it in `properties`.
@JsonPropertyOrder({"type", "id", "geometry", "properties"})
public record Feature<P>(String id, Point geometry, P properties) {
    @JsonProperty
    public String type() {
        return "Feature";
    }
}
