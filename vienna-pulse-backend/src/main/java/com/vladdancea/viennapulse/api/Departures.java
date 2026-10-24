package com.vladdancea.viennapulse.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.vladdancea.viennapulse.live.LiveStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// The next departures from a stop, soonest first, and whether Wiener Linien's live times for them
/// have been fetched.
public record Departures(Stop stop, LiveStatus liveStatus, List<Departure> departures) {
    /// The stop, with the merged id and name the map shows.
    public record Stop(String id, String name) {}

    /// One trip leaving the stop: when the timetable plans it and when Wiener Linien expect it, if
    /// they know, both in Vienna time, on which line, towards where and from which of the stop's
    /// platforms.
    public record Departure(
            OffsetDateTime plannedTime,
            @Nullable OffsetDateTime liveTime,
            Line line,
            @Nullable String headsign,
            String platform) {}

    /// A line by its id, with the same properties the map draws it with.
    public record Line(String id, @JsonUnwrapped LineProperties properties) {}
}
