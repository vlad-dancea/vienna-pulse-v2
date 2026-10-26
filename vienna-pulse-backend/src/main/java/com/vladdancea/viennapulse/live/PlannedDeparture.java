package com.vladdancea.viennapulse.live;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/// A departure in the timetable as the live times pair them: the ids of its trip and route, its
/// line's name, the trip's headsign and direction id, 0 or 1, and when it is planned.
public record PlannedDeparture(
        String trip,
        String route,
        String line,
        @Nullable String headsign,
        @Nullable Integer direction,
        Instant time) {}
