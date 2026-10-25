package com.vladdancea.viennapulse.live;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A station's live departures and whether they have been fetched, empty unless it is `live`, with
/// the whole seconds until the next round when it is `pending`.
public record Live(LiveStatus status, @Nullable Integer liveIn, List<LiveDeparture> departures) {
    static final Live UNAVAILABLE = new Live(LiveStatus.UNAVAILABLE, null, List.of());

    static Live pending(int liveIn) {
        return new Live(LiveStatus.PENDING, liveIn, List.of());
    }

    static Live live(List<LiveDeparture> departures) {
        return new Live(LiveStatus.LIVE, null, departures);
    }

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
