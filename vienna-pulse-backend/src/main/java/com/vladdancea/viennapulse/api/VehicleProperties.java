package com.vladdancea.viennapulse.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.jspecify.annotations.Nullable;

/// What the map knows about a vehicle besides where it is: the id of its line, the properties the
/// map draws that line with, and where the trip is headed.
public record VehicleProperties(
        String line,
        @JsonUnwrapped LineProperties properties,
        @Nullable String headsign) {}
