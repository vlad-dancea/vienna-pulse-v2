package com.vladdancea.viennapulse.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// What the map knows about a vehicle besides where it is: the id of its line, the properties the
/// map draws that line with, where the trip is headed, how many seconds behind its timetable the
/// live times put it at the start of its path, negative when ahead and `null` when the timetable
/// alone places it, and the path it follows next.
public record VehicleProperties(
        String line,
        @JsonUnwrapped LineProperties properties,
        @Nullable String headsign,
        @Nullable Integer delay,
        List<Waypoint> path) {}
