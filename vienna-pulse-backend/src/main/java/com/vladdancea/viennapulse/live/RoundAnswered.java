package com.vladdancea.viennapulse.live;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// The live departures of the stations a round asked about, as Wiener Linien answered, and when the
/// round asked.
record RoundAnswered(Instant at, List<Seen> departures) {
    /// A live departure from a platform of `station`, with the timetable departure it pairs with, or
    /// `null` when it pairs with none or the board has not read the station's timetable yet.
    record Seen(int station, LiveDeparture live, @Nullable PlannedDeparture planned) {}
}
