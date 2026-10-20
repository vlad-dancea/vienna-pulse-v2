package com.vladdancea.viennapulse.api.geojson;

/// The shape of a [Feature] on the map.
public sealed interface Geometry permits Point, MultiLineString {}
