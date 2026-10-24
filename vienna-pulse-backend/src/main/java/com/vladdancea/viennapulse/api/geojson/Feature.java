package com.vladdancea.viennapulse.api.geojson;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One thing on the map: where it is as a `G`, and what to show about it in `properties`.
@JsonPropertyOrder({"type", "id", "geometry", "properties"})
public record Feature<G extends Geometry, P>(String id, G geometry, P properties) {
    @JsonProperty
    public String type() {
        return "Feature";
    }
}
