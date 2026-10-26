package com.vladdancea.viennapulse.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/// How late Wiener Linien expect the trips that the rounds paired with a live departure, so that the
/// map can put their vehicles where the live times do.
///
/// A trip's delay is its live time minus its planned time in the timetable, from the last round that
/// listed it, at the station it leaves first if that round listed it at several. It holds for the
/// rest of the trip, also once the trip has left the stations people opened, since Wiener Linien
/// themselves expect a late vehicle to stay late at its later stops.
///
/// The live data can mislead. During a U3 disruption on 2026-10-24 Wiener Linien expected a train
/// planned for 20:15 at 21:02, and one planned for 20:33 at 20:23, and on 2026-10-26 they expected
/// buses 78A planned that morning up to two and a quarter hours late. So a delay counts only from
/// [#EARLIEST] to [#LATEST], and a trip whose live time falls outside goes back to its timetable.
@Component
@RequiredArgsConstructor
public class TripDelays {
    /// The earliest a trip may run. Of the 1,686 paired departures recorded by 2026-10-26, Wiener
    /// Linien expected none more than 185 seconds before they planned it, and their planned times
    /// differ from the timetable's by up to two minutes.
    static final Duration EARLIEST = Duration.ofMinutes(-5);

    /// The latest a trip may run. Of the same departures, 95 in 100 were at most 188 seconds late, a
    /// few up to 11 minutes, and none between that and an hour.
    static final Duration LATEST = Duration.ofMinutes(20);

    // Longer than the 70 minutes ahead that a round lists plus the longest trip of the timetable, 150
    // minutes, so a delay outlasts its trip, and shorter than the day until the trip runs again.
    private static final Duration KEPT = Duration.ofHours(4);

    private final Clock clock;

    private final Map<String, TripDelay> delays = new ConcurrentHashMap<>();

    /// Takes the delays of the trips that `answered` pairs with a live departure that has a live time.
    @EventListener
    void record(RoundAnswered answered) {
        var learned = clock.instant();
        var first = new HashMap<String, Times>();
        for (var seen : answered.departures()) {
            var planned = seen.planned();
            var real = seen.live().real();
            if (planned != null && real != null) {
                // The timetable orders the stations along the trip, which predictions may not.
                first.merge(
                        planned.trip(),
                        new Times(planned.time(), real),
                        (one, other) -> other.planned().isBefore(one.planned()) ? other : one);
            }
        }
        first.forEach((trip, times) -> {
            var delay = Duration.between(times.planned(), times.real());
            if (delay.compareTo(EARLIEST) >= 0 && delay.compareTo(LATEST) <= 0) {
                delays.put(trip, new TripDelay(delay, learned));
            } else {
                delays.computeIfPresent(trip, (any, before) -> new TripDelay(Duration.ZERO, learned));
            }
        });
        delays.values().removeIf(delay -> !delay.learned().plus(KEPT).isAfter(learned));
    }

    /// The delay of each trip that a round listed in the hours before `now`, by the trip's id.
    public Map<String, TripDelay> at(Instant now) {
        var recent = new HashMap<String, TripDelay>();
        delays.forEach((trip, delay) -> {
            if (delay.learned().plus(KEPT).isAfter(now)) {
                recent.put(trip, delay);
            }
        });
        return recent;
    }

    /// How late a trip runs, negative when early, and when the backend learned it.
    public record TripDelay(Duration delay, Instant learned) {}

    // When the timetable plans a trip to leave a station and when Wiener Linien expect it to.
    private record Times(Instant planned, Instant real) {}
}
