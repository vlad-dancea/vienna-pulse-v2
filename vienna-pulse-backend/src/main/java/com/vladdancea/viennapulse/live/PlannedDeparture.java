package com.vladdancea.viennapulse.live;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/// A departure in the timetable as the live times pair them: its line's name, the trip's
/// direction id, 0 or 1, and when it is planned.
public record PlannedDeparture(String line, @Nullable Integer direction, Instant time) {}
