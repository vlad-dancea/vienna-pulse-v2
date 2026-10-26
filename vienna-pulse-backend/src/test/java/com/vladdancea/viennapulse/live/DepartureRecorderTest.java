package com.vladdancea.viennapulse.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

// The same setup as the controller tests, so that all of them share one application and database.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class DepartureRecorderTest {
    // Stephansplatz and a platform of its U1, which the answers of MonitorServer never list, so the
    // rounds of other tests leave its rows alone.
    private static final int STATION = 60_201_320;
    private static final int PLATFORM = 4111;

    @Autowired
    DepartureRecorder recorder;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void forgetTheStation() {
        jdbc.sql("DELETE FROM public.live_departures WHERE station = :station")
                .param("station", STATION)
                .update();
    }

    // The first round starts before the board reads the timetable, and a later one may pair the
    // departure with none, as when the board read the timetable more than an hour after it was planned.
    @Test
    void keepsTheLastPredictionOfADepartureSeenInSeveralRounds() {
        var trip = new PlannedDeparture("u1-leopoldau", "u1", "U1", "Wien Leopoldau", 0, vienna("21:50:00"));
        recorder.record(answered("21:47:40", departure("21:50:00", "21:52:00", 4, null)));
        recorder.record(answered("21:48:10", departure("21:50:00", "21:52:30", 4, trip)));
        recorder.record(answered("21:52:00", departure("21:50:00", "21:52:40", 0, null)));

        assertThat(recorded())
                .containsExactly(new Recorded(
                        PLATFORM,
                        "U1",
                        "H",
                        vienna("21:50:00"),
                        STATION,
                        vienna("21:52:40"),
                        0,
                        vienna("21:47:40"),
                        vienna("21:52:00"),
                        "u1-leopoldau",
                        "u1"));
        assertThat(observedDelays()).containsExactly(Duration.ofSeconds(160));
    }

    // The board closed the station after the second round, ten minutes before the second train's
    // live time, so only the first train was seen leaving.
    @Test
    void observesOnlyTheDeparturesARoundListedAMinuteOrLessBeforeTheyLeft() {
        recorder.record(answered(
                "21:47:40", departure("21:49:00", "21:49:30", 2, null), departure("21:59:00", "21:59:00", 12, null)));
        recorder.record(answered(
                "21:48:40", departure("21:49:00", "21:49:30", 1, null), departure("21:59:00", "21:59:20", 11, null)));

        assertThat(recorded()).hasSize(2);
        assertThat(observedDelays()).containsExactly(Duration.ofSeconds(30));
    }

    // The timetable departure pairs with one of the two.
    @Test
    void keepsTheTripOfADepartureAnAnswerListsTwice() {
        var trip = new PlannedDeparture("u1-leopoldau", "u1", "U1", "Wien Leopoldau", 0, vienna("21:50:00"));
        recorder.record(answered(
                "21:47:40",
                departure("21:50:00", "21:52:00", 4, null),
                departure("21:50:00", "21:52:00", 4, trip),
                departure("21:50:00", "21:52:00", 4, null)));

        assertThat(recorded()).extracting(Recorded::tripId).containsExactly("u1-leopoldau");
    }

    private List<Recorded> recorded() {
        return jdbc.sql("""
                        SELECT * FROM public.live_departures
                        WHERE station = :station
                        ORDER BY planned_time
                        """)
                .param("station", STATION)
                .query((row, number) -> new Recorded(
                        row.getInt("platform"),
                        row.getString("line"),
                        row.getString("direction"),
                        instant(row.getObject("planned_time", OffsetDateTime.class)),
                        row.getInt("station"),
                        instant(row.getObject("live_time", OffsetDateTime.class)),
                        row.getInt("countdown"),
                        instant(row.getObject("first_seen", OffsetDateTime.class)),
                        instant(row.getObject("last_seen", OffsetDateTime.class)),
                        row.getString("trip_id"),
                        row.getString("route_id")))
                .list();
    }

    private List<Duration> observedDelays() {
        return jdbc.sql("""
                        SELECT CAST(extract(epoch FROM delay) AS integer) FROM public.observed_departures
                        WHERE station = :station
                        ORDER BY planned_time
                        """)
                .param("station", STATION)
                .query((row, number) -> Duration.ofSeconds(row.getInt(1)))
                .list();
    }

    // A round's answer at `time` in Vienna on 2026-10-24.
    private static RoundAnswered answered(String time, RoundAnswered.Seen... departures) {
        return new RoundAnswered(vienna(time), List.of(departures));
    }

    // A U1 towards Leopoldau, planned for `planned` and expected at `real` in Vienna on 2026-10-24.
    private static RoundAnswered.Seen departure(
            String planned, String real, int countdown, @Nullable PlannedDeparture trip) {
        return new RoundAnswered.Seen(
                STATION,
                new LiveDeparture(PLATFORM, "U1", "H", "Leopoldau", vienna(planned), vienna(real), countdown),
                trip);
    }

    private static Instant vienna(String time) {
        return Instant.parse("2026-10-24T" + time + "+02:00");
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    // A row of the table.
    private record Recorded(
            int platform,
            String line,
            String direction,
            @Nullable Instant plannedTime,
            int station,
            @Nullable Instant liveTime,
            int countdown,
            @Nullable Instant firstSeen,
            @Nullable Instant lastSeen,
            @Nullable String tripId,
            @Nullable String routeId) {}
}
