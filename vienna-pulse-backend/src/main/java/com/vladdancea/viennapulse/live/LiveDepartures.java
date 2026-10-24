package com.vladdancea.viennapulse.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/// Wiener Linien's live departures for the stations the board has open, fetched in rounds that keep
/// the fair-use rules of their realtime API.
///
/// A departures request keeps its stop's station open for `pulse.live.open-for`. Each round sends
/// one `monitor` request for the platforms of the open stations, at most `pulse.live.max-platforms`
/// of them, starting with the stations whose answer is oldest, and the next round starts
/// `pulse.live.round-interval` after it ends. So the backend asks at most once per interval however
/// many people use the map, and not at all while nobody has a stop open. When Wiener Linien refuse
/// a request over their limits, it stops asking for `pulse.live.blocked-pause`.
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveDepartures {
    // Four rounds. When so many stations are open that one waits longer for its turn, its board shows
    // the planned times instead of predictions that old.
    private static final Duration MAX_AGE = Duration.ofMinutes(1);

    private final MonitorClient monitor;
    private final Platforms platforms;
    private final LiveProperties properties;
    private final Clock clock;

    // When each open station closes. Requests write it, and rounds drop the stations that closed.
    private final Map<Integer, Instant> openUntil = new ConcurrentHashMap<>();
    // The last answer for each open station, which only rounds write.
    private final Map<Integer, Answer> answers = new ConcurrentHashMap<>();
    // Only rounds write it.
    private volatile Instant pausedUntil = Instant.MIN;

    /// The live departures of the stop with the merged id `stopId`, like `at:49:1320`, as a round in
    /// the last minute fetched them, and keeps its station open until `pulse.live.open-for` after `now`.
    public Live open(String stopId, Instant now) {
        var station = Platforms.station(stopId);
        if (station == null || platforms.of(station).isEmpty()) {
            return Live.UNAVAILABLE;
        }
        openUntil.put(station, now.plus(properties.openFor()));
        if (now.isBefore(pausedUntil)) {
            return Live.UNAVAILABLE;
        }
        var answer = answers.get(station);
        if (answer == null || !answer.at().plus(MAX_AGE).isAfter(now)) {
            return Live.PENDING;
        }
        var departures = answer.departures();
        return departures == null ? Live.UNAVAILABLE : new Live(LiveStatus.LIVE, departures);
    }

    /// Sends one request for the platforms of the open stations, unless Wiener Linien blocked the
    /// backend or no station is open.
    @Scheduled(
            initialDelayString = "${pulse.live.round-interval}",
            fixedDelayString = "${pulse.live.round-interval}",
            scheduler = LiveConfiguration.ROUNDS)
    public void round() {
        var now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return;
        }
        openUntil.values().removeIf(until -> !until.isAfter(now));
        answers.keySet().retainAll(openUntil.keySet());
        var stations = new ArrayList<Integer>();
        var batch = new ArrayList<Integer>();
        // The stations that did not fit into the last request have the oldest answers, so they go first.
        for (var station : openUntil.keySet().stream()
                .sorted(Comparator.comparing(this::answeredAt).thenComparing(Comparator.naturalOrder()))
                .toList()) {
            var numbers = platforms.of(station);
            if (batch.size() + numbers.size() > properties.maxPlatforms()) {
                break;
            }
            stations.add(station);
            batch.addAll(numbers);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            var departures = new HashMap<Integer, List<LiveDeparture>>();
            for (var departure : monitor.departures(batch)) {
                var station = platforms.stationOf(departure.platform());
                if (station != null) {
                    departures
                            .computeIfAbsent(station, any -> new ArrayList<>())
                            .add(departure);
                }
            }
            for (var station : stations) {
                answers.put(station, new Answer(now, List.copyOf(departures.getOrDefault(station, List.of()))));
            }
        } catch (MonitorClient.Blocked exception) {
            pausedUntil = now.plus(properties.blockedPause());
            // After the pause the stations wait for a new answer instead of showing one minutes old.
            answers.clear();
            log.error(
                    "Wiener Linien refused the live departures, asking again in {}: {}",
                    properties.blockedPause(),
                    exception.getMessage());
        } catch (RuntimeException exception) {
            for (var station : stations) {
                answers.put(station, new Answer(now, null));
            }
            // The cause alone, because the request's address lists up to 100 platform numbers.
            log.warn(
                    "Could not fetch the live departures of {} stations: {}",
                    stations.size(),
                    NestedExceptionUtils.getMostSpecificCause(exception).toString());
        }
    }

    private Instant answeredAt(int station) {
        var answer = answers.get(station);
        return answer == null ? Instant.MIN : answer.at();
    }

    // When a round asked about the station, and its departures, or null when the request failed.
    private record Answer(Instant at, @Nullable List<LiveDeparture> departures) {}
}
