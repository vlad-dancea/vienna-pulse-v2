package com.vladdancea.viennapulse.live;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A station's live departures and whether they have been fetched, empty unless it is `live`.
public record Live(LiveStatus status, List<LiveDeparture> departures) {
    static final Live PENDING = new Live(LiveStatus.PENDING, List.of());
    static final Live UNAVAILABLE = new Live(LiveStatus.UNAVAILABLE, List.of());

    /// When Wiener Linien expect each departure of `timetable`, in the same order, or `null` where
    /// they have no live time for it.
    public List<@Nullable Instant> times(List<PlannedDeparture> timetable) {
        var times = new ArrayList<@Nullable Instant>();
        for (var departure : Matching.pair(timetable, departures)) {
            times.add(departure == null ? null : departure.real());
        }
        return times;
    }
}
