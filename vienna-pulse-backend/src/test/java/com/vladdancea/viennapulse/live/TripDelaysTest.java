package com.vladdancea.viennapulse.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestClock;
import com.vladdancea.viennapulse.live.TripDelays.TripDelay;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TripDelaysTest {
    private static final int STEPHANSPLATZ = 60_201_320;
    private static final int KARLSPLATZ = 60_200_657;

    final TestClock clock = new TestClock();

    final TripDelays delays = new TripDelays(clock);

    // The U1 is expected to leave Karlsplatz a minute late, and Stephansplatz, its next station, at a
    // time that would have it leave there first. The U3 runs half a minute early, the board has not
    // read the trip of the third departure, and the 2A has no live time.
    @Test
    void takesEachTripsDelayFromTheFirstStationItLeavesInTheLastRound() {
        clock.set("2026-10-24T19:47:55Z");
        delays.record(answered(
                seen(STEPHANSPLATZ, "u1", "21:52:00", "21:50:50"),
                seen(KARLSPLATZ, "u1", "21:50:00", "21:51:00"),
                seen(STEPHANSPLATZ, "u3", "21:50:00", "21:49:30"),
                seen(STEPHANSPLATZ, null, "21:50:00", "21:51:00"),
                seen(STEPHANSPLATZ, "2a", "21:50:00", null)));
        var first = clock.instant();

        assertThat(delays.at(first))
                .isEqualTo(Map.of(
                        "u1", new TripDelay(Duration.ofSeconds(60), first),
                        "u3", new TripDelay(Duration.ofSeconds(-30), first)));

        clock.set("2026-10-24T19:48:10Z");
        delays.record(answered(seen(KARLSPLATZ, "u1", "21:50:00", "21:51:20")));

        assertThat(delays.at(clock.instant()))
                .isEqualTo(Map.of(
                        "u1", new TripDelay(Duration.ofSeconds(80), clock.instant()),
                        "u3", new TripDelay(Duration.ofSeconds(-30), first)));
    }

    // As during the U3 disruption on 2026-10-24, a train is expected 47 minutes late and another
    // ten minutes early, while two others keep within the bounds.
    @Test
    void sendsATripWhoseLiveTimeIsFarOffBackToItsTimetable() {
        clock.set("2026-10-24T19:00:00Z");
        delays.record(answered(seen(STEPHANSPLATZ, "u3-late", "20:15:00", "20:17:00")));

        clock.set("2026-10-24T19:30:00Z");
        delays.record(answered(
                seen(STEPHANSPLATZ, "u3-late", "20:15:00", "21:02:00"),
                seen(STEPHANSPLATZ, "u3-early", "20:33:00", "20:23:00"),
                seen(STEPHANSPLATZ, "u3-latest", "20:40:00", "21:00:00"),
                seen(STEPHANSPLATZ, "u3-earliest", "20:45:00", "20:40:00")));

        assertThat(delays.at(clock.instant()))
                .isEqualTo(Map.of(
                        "u3-late", new TripDelay(Duration.ZERO, clock.instant()),
                        "u3-latest", new TripDelay(Duration.ofMinutes(20), clock.instant()),
                        "u3-earliest", new TripDelay(Duration.ofMinutes(-5), clock.instant())));
    }

    // The longest trip of the timetable takes two and a half hours, after a round listed it up to 70
    // minutes ahead.
    @Test
    void forgetsADelayFourHoursAfterTheLastRoundThatListedIt() {
        clock.set("2026-10-24T19:47:55Z");
        delays.record(answered(seen(STEPHANSPLATZ, "u2", "21:50:00", "21:51:00")));
        var learned = clock.instant();

        assertThat(delays.at(Instant.parse("2026-10-24T23:47:54Z")))
                .isEqualTo(Map.of("u2", new TripDelay(Duration.ofMinutes(1), learned)));
        assertThat(delays.at(Instant.parse("2026-10-24T23:47:55Z"))).isEmpty();

        // A round then drops it, which a look from just before shows.
        clock.set("2026-10-24T23:47:55Z");
        delays.record(answered(seen(STEPHANSPLATZ, "u1", "01:50:00", "01:51:00")));

        assertThat(delays.at(Instant.parse("2026-10-24T23:47:54Z"))).containsOnlyKeys("u1");
    }

    private RoundAnswered answered(RoundAnswered.Seen... departures) {
        return new RoundAnswered(clock.instant().minusSeconds(1), List.of(departures));
    }

    // A departure of `trip` from `station`, planned in the timetable at `planned` and expected at
    // `real` in Vienna on 2026-10-24, or of a trip the board has not read when `trip` is null.
    private static RoundAnswered.Seen seen(int station, @Nullable String trip, String planned, @Nullable String real) {
        var live =
                new LiveDeparture(4111, "U1", "H", "Leopoldau", vienna(planned), real == null ? null : vienna(real), 3);
        return new RoundAnswered.Seen(
                station,
                live,
                trip == null ? null : new PlannedDeparture(trip, "u1", "U1", "Wien Leopoldau", 0, vienna(planned)));
    }

    private static Instant vienna(String time) {
        return Instant.parse("2026-10-24T" + time + "+02:00");
    }
}
