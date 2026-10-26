package com.vladdancea.viennapulse.live;

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

    /// The live departure paired with each departure of `timetable`, in the same order, or `null` for
    /// the departures without one.
    public List<@Nullable LiveDeparture> pair(List<PlannedDeparture> timetable) {
        return Matching.pair(timetable, departures);
    }
}
