package com.vladdancea.viennapulse.api.geojson;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/// A list of features with the same kind of geometry, the top level of a GeoJSON document.
@JsonPropertyOrder({"type", "features"})
public record FeatureCollection<G extends Geometry, P>(List<Feature<G, P>> features) {
    public static final String MEDIA_TYPE = "application/geo+json";

    @JsonProperty
    public String type() {
        return "FeatureCollection";
    }
}
