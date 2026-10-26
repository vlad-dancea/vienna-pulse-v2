package com.vladdancea.viennapulse.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.vladdancea.viennapulse.live.LiveStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// The next departures from a stop, soonest first, whether Wiener Linien's live times for them have
/// been fetched, and while they are `pending`, the whole seconds until the round that should fetch
/// them, or `null` otherwise.
public record Departures(
        Stop stop, LiveStatus liveStatus, @Nullable Integer liveIn, List<Departure> departures) {
    /// The stop, with the merged id and name the map shows.
    public record Stop(String id, String name) {}

    /// One trip leaving the stop: when the timetable plans it and when Wiener Linien expect it, if
    /// they know, both in Vienna time, on which line, towards where, from which of the stop's
    /// platforms, and the code signs show for the platform, such as `1` or `C`, if Wiener Linien's
    /// live data lists the trip at a platform that has one.
    public record Departure(
            OffsetDateTime plannedTime,
            @Nullable OffsetDateTime liveTime,
            Line line,
            @Nullable String headsign,
            String platform,
            @Nullable String platformCode) {}

    /// A line by its id, with the same properties the map draws it with.
    public record Line(String id, @JsonUnwrapped LineProperties properties) {}
}
