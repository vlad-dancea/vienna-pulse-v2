package com.vladdancea.viennapulse.api.geojson;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/// Several lines drawn as one, each a list of at least two positions.
@JsonPropertyOrder({"type", "coordinates"})
public record MultiLineString(List<List<Position>> coordinates) implements Geometry {
    @JsonProperty
    public String type() {
        return "MultiLineString";
    }
}
