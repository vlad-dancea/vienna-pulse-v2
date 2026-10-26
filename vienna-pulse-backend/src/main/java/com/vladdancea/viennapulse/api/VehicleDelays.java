package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.live.TripDelays;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/// How far behind its timetable each vehicle runs while the live times say that its trip runs late
/// or early.
///
/// A vehicle's delay follows its trip's, so that the vehicle neither jumps nor turns back when the
/// trip's delay appears or changes. Maps move their vehicles along the paths they already have, so
/// it changes only after those paths end, and then by at most [#RATE] seconds each second until it
/// is the trip's.
@Component
@RequiredArgsConstructor
class VehicleDelays {
    /// How fast a vehicle's delay changes: half a second each second, so that a vehicle falling behind
    /// its timetable moves at half its speed and one catching up at one and a half times.
    static final double RATE = 0.5;

    // An answer worked out before the backend learned a trip's delay has paths that end at most 30
    // seconds after that, since its step started earlier. A step more leaves room for an answer that
    // read the clock just as the delay arrived.
    private static final Duration WAIT = VehicleService.PATH.plus(ApiConfiguration.VEHICLE_STEP);

    // An answer for a step can be worked out after the next one, when its request came late. Points
    // before that matter to no answer.
    private static final double FORGOTTEN = Duration.ofMinutes(1).toSeconds();

    private final TripDelays trips;

    // The delays of the vehicles over time, by trip id, in seconds since the epoch. The answers work
    // them out one at a time, which keeps the paths of every answer the same where they overlap.
    private final Map<String, Delay> delays = new HashMap<>();

    /// The delays of the vehicles that run off their timetable on the path that starts at
    /// `stepStart`, by trip id, with the seconds counted from `stepStart`.
    synchronized Map<String, Delay> along(Instant stepStart) {
        var from = seconds(stepStart);
        var to = from + VehicleService.PATH.toSeconds();
        var known = trips.at(stepStart);
        // A trip that a round listed hours ago has ended.
        delays.keySet().retainAll(known.keySet());
        var along = new HashMap<String, Delay>();
        known.forEach((trip, tripDelay) -> {
            var target = tripDelay.delay().toMillis() / 1000.0;
            var delay = delays.getOrDefault(trip, Delay.NONE);
            if (delay.last() != target) {
                delay = delay.towards(target, seconds(tripDelay.learned().plus(WAIT)), RATE);
            }
            delay = delay.since(from - FORGOTTEN);
            delays.put(trip, delay);
            var path = delay.between(from, to);
            if (!path.none()) {
                along.put(trip, path);
            }
        });
        return along;
    }

    private static double seconds(Instant instant) {
        return instant.getEpochSecond() + instant.getNano() / 1e9;
    }
}
