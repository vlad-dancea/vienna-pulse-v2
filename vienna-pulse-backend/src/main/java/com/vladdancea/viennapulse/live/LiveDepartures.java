package com.vladdancea.viennapulse.live;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/// Wiener Linien's live departures for the stations the board has open, fetched in rounds that keep
/// the fair-use rules of their realtime API.
///
/// A departures request keeps its stop's station open for `pulse.live.open-for`. Each round sends
/// one `monitor` request for the platforms of the open stations, at most `pulse.live.max-platforms`
/// of them, starting with the stations whose answer is oldest. One gate decides when a round may
/// start: `pulse.live.round-interval` after the last request ended, and never while one runs. A
/// departures request whose station has no answer from the last minute starts a round itself when
/// the gate allows, and a loop that checks the gate every `pulse.live.check-interval` starts the
/// others, which keep the answers of the open stations fresh. So the backend asks at most once per
/// interval however many people use the map, and not at all while nobody has a stop open. When
/// Wiener Linien refuse a request over their limits, the gate stays shut for
/// `pulse.live.blocked-pause`.
@Component
@Slf4j
public class LiveDepartures {
    // Four rounds. When so many stations are open that one waits longer for its turn, its board waits
    // for a new answer instead of showing predictions that old.
    private static final Duration MAX_AGE = Duration.ofMinutes(1);

    private final MonitorClient monitor;
    private final Platforms platforms;
    private final LiveProperties properties;
    private final Clock clock;
    // Times the gate, because setting the system clock forward must not shorten the wait between two
    // requests.
    private final InstantSource steadyClock;

    // When each open station closes. Requests write it, and rounds drop the stations that closed.
    private final Map<Integer, Instant> openUntil = new ConcurrentHashMap<>();
    // The last answer for each open station, which only rounds write.
    private final Map<Integer, Answer> answers = new ConcurrentHashMap<>();

    // Guards the gate's three fields below, so that two threads never both find it open.
    private final Object gate = new Object();
    // When the next request may start. The process this one replaced may have asked just before it
    // stopped, so the first waits one interval too.
    private Instant nextRequest;
    private Instant pausedUntil = Instant.MIN;
    private @Nullable Round running;

    @Autowired
    public LiveDepartures(MonitorClient monitor, Platforms platforms, LiveProperties properties, Clock clock) {
        this(monitor, platforms, properties, clock, steady());
    }

    // Tests pass their clock twice, which moves both clocks at once.
    LiveDepartures(
            MonitorClient monitor,
            Platforms platforms,
            LiveProperties properties,
            Clock clock,
            InstantSource steadyClock) {
        this.monitor = monitor;
        this.platforms = platforms;
        this.properties = properties;
        this.clock = clock;
        this.steadyClock = steadyClock;
        this.nextRequest = steadyClock.instant().plus(properties.roundInterval());
    }

    /// Keeps the station of the stop with the merged id `stopId`, like `at:49:1320`, open until
    /// `pulse.live.open-for` after `now`. When the station has no answer from the last minute and the
    /// gate is open, it starts a round on a thread of its own, so the request can read the timetable
    /// meanwhile and then call [#await].
    public void open(String stopId, Instant now) {
        var station = station(stopId);
        if (station == null) {
            return;
        }
        openUntil.put(station, now.plus(properties.openFor()));
        if (answer(station, now) == null) {
            var round = start();
            if (round != null) {
                Thread.ofVirtual().name("live-round").start(() -> run(round));
            }
        }
    }

    /// The live departures of the stop with the merged id `stopId`, like `at:49:1320`. When its station
    /// has no answer from the last minute and a round is running, it waits for that round, but no
    /// longer than `pulse.live.timeout` after the round started. The round either fetches the station
    /// or sets when the next one may start.
    public Live await(String stopId, Instant now) {
        var station = station(stopId);
        if (station == null) {
            return Live.UNAVAILABLE;
        }
        var answer = answer(station, now);
        if (answer == null) {
            var round = running();
            if (round != null && !endsInTime(round) && round.stations().contains(station)) {
                // Wiener Linien took too long, which the round is about to record.
                return Live.UNAVAILABLE;
            }
            answer = answer(station, now);
        }
        synchronized (gate) {
            var steadyNow = steadyClock.instant();
            if (steadyNow.isBefore(pausedUntil)) {
                return Live.UNAVAILABLE;
            }
            if (answer == null) {
                return Live.pending(secondsToNextRound(steadyNow));
            }
        }
        var departures = answer.departures();
        return departures == null ? Live.UNAVAILABLE : Live.live(departures);
    }

    /// Starts a round when the gate is open and a station is open, and waits for it to end.
    @Scheduled(
            initialDelayString = "${pulse.live.check-interval}",
            fixedDelayString = "${pulse.live.check-interval}",
            scheduler = LiveConfiguration.ROUNDS)
    public void round() {
        var round = start();
        if (round != null) {
            run(round);
        }
    }

    // Takes the gate for a round of the open stations, or returns null when it is shut or none is open.
    private @Nullable Round start() {
        synchronized (gate) {
            var steadyNow = steadyClock.instant();
            if (running != null || steadyNow.isBefore(nextRequest)) {
                return null;
            }
            var now = clock.instant();
            openUntil.values().removeIf(until -> !until.isAfter(now));
            answers.keySet().retainAll(openUntil.keySet());
            var stations = new ArrayList<Integer>();
            var batch = new ArrayList<Integer>();
            // The stations that did not fit into the last request have the oldest answers, so they go
            // first, and a station nobody asked about yet goes before them.
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
                return null;
            }
            running = new Round(
                    List.copyOf(stations),
                    List.copyOf(batch),
                    now,
                    steadyNow.plus(properties.timeout()),
                    new CountDownLatch(1));
            return running;
        }
    }

    // Sends the round's request, records the answers and opens the gate again an interval later.
    private void run(Round round) {
        var blocked = false;
        try {
            var departures = new HashMap<Integer, List<LiveDeparture>>();
            for (var departure : monitor.departures(round.platforms())) {
                var station = platforms.stationOf(departure.platform());
                if (station != null) {
                    departures
                            .computeIfAbsent(station, any -> new ArrayList<>())
                            .add(departure);
                }
            }
            for (var station : round.stations()) {
                answers.put(station, new Answer(round.at(), List.copyOf(departures.getOrDefault(station, List.of()))));
            }
        } catch (MonitorClient.Blocked exception) {
            blocked = true;
            // After the pause the stations wait for a new answer instead of showing one minutes old.
            answers.clear();
            log.error(
                    "Wiener Linien refused the live departures, asking again in {}: {}",
                    properties.blockedPause(),
                    exception.getMessage());
        } catch (RuntimeException exception) {
            for (var station : round.stations()) {
                answers.put(station, new Answer(round.at(), null));
            }
            // The cause alone, because the request's address lists up to 100 platform numbers.
            log.warn(
                    "Could not fetch the live departures of {} stations: {}",
                    round.stations().size(),
                    NestedExceptionUtils.getMostSpecificCause(exception).toString());
        } finally {
            synchronized (gate) {
                var end = steadyClock.instant();
                if (blocked) {
                    pausedUntil = end.plus(properties.blockedPause());
                }
                nextRequest = later(end.plus(properties.roundInterval()), pausedUntil);
                running = null;
            }
            round.done().countDown();
        }
    }

    // The station of a stop that has one in the realtime API, or null.
    private @Nullable Integer station(String stopId) {
        var station = Platforms.station(stopId);
        return station == null || platforms.of(station).isEmpty() ? null : station;
    }

    // The station's answer from the last minute, or null.
    private @Nullable Answer answer(int station, Instant now) {
        var answer = answers.get(station);
        return answer != null && answer.at().plus(MAX_AGE).isAfter(now) ? answer : null;
    }

    private Instant answeredAt(int station) {
        var answer = answers.get(station);
        return answer == null ? Instant.MIN : answer.at();
    }

    private @Nullable Round running() {
        synchronized (gate) {
            return running;
        }
    }

    // Waits for the round to end, and tells whether it did before its deadline.
    private boolean endsInTime(Round round) {
        var left = Duration.between(steadyClock.instant(), round.deadline());
        try {
            return round.done().await(Math.max(0, left.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Whole seconds, rounded up, until the next round may start, and at least one, which the loop may
    // take to see the gate open. A round still running after its deadline is about to end.
    private int secondsToNextRound(Instant steadyNow) {
        var next =
                running != null ? later(running.deadline(), steadyNow).plus(properties.roundInterval()) : nextRequest;
        var nanos = Duration.between(steadyNow, next).toNanos();
        return (int) Math.max(1, Math.ceilDiv(nanos, Duration.ofSeconds(1).toNanos()));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    // Counts from an arbitrary instant and only moves on, unlike the system clock.
    private static InstantSource steady() {
        var origin = System.nanoTime();
        return () -> Instant.EPOCH.plusNanos(System.nanoTime() - origin);
    }

    // When a round asked about the station, and its departures, or null when the request failed.
    private record Answer(Instant at, @Nullable List<LiveDeparture> departures) {}

    // The stations a round asks about and their platforms, when it started on the system clock, when
    // it times out on the steady clock, and a latch that opens once it has recorded its answers.
    private record Round(
            List<Integer> stations, List<Integer> platforms, Instant at, Instant deadline, CountDownLatch done) {}
}
