package com.vladdancea.viennapulse.importer.ingestor;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;

/** The basic GTFS route types. */
@RequiredArgsConstructor
public enum RouteType {
    TRAM(0),
    SUBWAY(1),
    RAIL(2),
    BUS(3),
    FERRY(4),
    CABLE_TRAM(5),
    AERIAL_LIFT(6),
    FUNICULAR(7),
    TROLLEYBUS(11),
    MONORAIL(12);

    private final int code;

    public static RouteType fromCode(int code) {
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown route type " + code));
    }
}
