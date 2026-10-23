package com.vladdancea.viennapulse.importer.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest
@Import(TestcontainersConfiguration.class)
class TimetableHealthIndicatorTest {
    // Half past midnight on Saturday 24 October in Vienna, still the 23rd in UTC.
    private static final Instant NOW = Instant.parse("2026-10-23T22:30:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-24");
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final LocalDate FIRST_DAY = LocalDate.parse("2026-10-01");

    @Autowired
    JdbcClient jdbc;

    // Every service the tests run has a trip, so that only the reason a test names turns it DOWN.
    @BeforeEach
    void giveEachServiceATrip() {
        jdbc.sql("""
                        INSERT INTO timetable.trips (id, route_id, service_id)
                        VALUES ('t0', 'U1', 'T0'), ('t1', 'U1', 'T1'), ('weekdays', 'U1', 'weekdays'),
                               ('daily', 'U1', 'daily')
                        """).update();
    }

    @Test
    void isUpWhileTheLastSuccessIsRecentAndTheTimetableCoversTomorrow() {
        // Exactly as old as allowed.
        var lastSuccess = NOW.minus(Duration.ofHours(36));
        recordImport("SUCCEEDED", lastSuccess);
        recordImport("FAILED", NOW.minus(Duration.ofHours(1)));
        runsUntil(TODAY);
        addedOn(TOMORROW);

        assertThat(health())
                .isEqualTo(Health.up()
                        .withDetail("lastSuccessfulImport", lastSuccess.toString())
                        .withDetail("coveredFrom", FIRST_DAY.toString())
                        .withDetail("coveredUntil", TOMORROW.toString())
                        .build());
    }

    @Test
    void isDownWhenTheLastSuccessIsTooOld() {
        var lastSuccess = NOW.minus(Duration.ofHours(37));
        recordImport("SUCCEEDED", lastSuccess);
        recordImport("FAILED", NOW.minus(Duration.ofHours(1)));
        runsUntil(TOMORROW);

        assertThat(health())
                .isEqualTo(Health.down()
                        .withDetail("lastSuccessfulImport", lastSuccess.toString())
                        .withDetail("coveredFrom", FIRST_DAY.toString())
                        .withDetail("coveredUntil", TOMORROW.toString())
                        .build());
    }

    // A day that calendar_dates.txt removes is no day the timetable covers.
    @Test
    void isDownWhenTheTimetableEndsToday() {
        var lastSuccess = NOW.minus(Duration.ofHours(1));
        recordImport("SUCCEEDED", lastSuccess);
        runsUntil(TODAY);
        jdbc.sql("INSERT INTO timetable.calendar_dates VALUES ('T0', :date, 'REMOVED')")
                .param("date", TOMORROW)
                .update();

        assertThat(health())
                .isEqualTo(Health.down()
                        .withDetail("lastSuccessfulImport", lastSuccess.toString())
                        .withDetail("coveredFrom", FIRST_DAY.toString())
                        .withDetail("coveredUntil", TODAY.toString())
                        .build());
    }

    // The lines endpoint answers 404 for a day before the timetable starts, as for one after it ends.
    @Test
    void isDownWhenTheTimetableStartsAfterTomorrow() {
        var lastSuccess = NOW.minus(Duration.ofHours(1));
        recordImport("SUCCEEDED", lastSuccess);
        var firstDay = TOMORROW.plusDays(1);
        var lastDay = LocalDate.parse("2026-12-12");
        runs(firstDay, lastDay);

        assertThat(health())
                .isEqualTo(Health.down()
                        .withDetail("lastSuccessfulImport", lastSuccess.toString())
                        .withDetail("coveredFrom", firstDay.toString())
                        .withDetail("coveredUntil", lastDay.toString())
                        .build());
    }

    // Tomorrow is a Sunday inside the timetable's days, but none of its services runs then.
    @Test
    void isDownWhenNoServiceRunsTomorrow() {
        var lastSuccess = NOW.minus(Duration.ofHours(1));
        recordImport("SUCCEEDED", lastSuccess);
        var lastDay = LocalDate.parse("2026-12-12");
        jdbc.sql("""
                        INSERT INTO timetable.service_calendars
                        VALUES ('weekdays', true, true, true, true, true, false, false, :firstDay, :lastDay),
                               ('daily', true, true, true, true, true, true, true, :firstDay, :lastDay)
                        """).param("firstDay", FIRST_DAY).param("lastDay", lastDay).update();
        jdbc.sql("INSERT INTO timetable.calendar_dates VALUES ('daily', :date, 'REMOVED')")
                .param("date", TOMORROW)
                .update();

        assertThat(health())
                .isEqualTo(Health.down()
                        .withDetail("lastSuccessfulImport", lastSuccess.toString())
                        .withDetail("coveredFrom", FIRST_DAY.toString())
                        .withDetail("coveredUntil", lastDay.toString())
                        .build());
    }

    // The lines endpoint draws no line for a service without trips.
    @Test
    void isDownWhenTheServiceThatRunsTomorrowHasNoTrips() {
        var lastSuccess = NOW.minus(Duration.ofHours(1));
        recordImport("SUCCEEDED", lastSuccess);
        runsUntil(TODAY);
        jdbc.sql("INSERT INTO timetable.calendar_dates VALUES ('tripless', :date, 'ADDED')")
                .param("date", TOMORROW)
                .update();

        assertThat(health())
                .isEqualTo(Health.down()
                        .withDetail("lastSuccessfulImport", lastSuccess.toString())
                        .withDetail("coveredFrom", FIRST_DAY.toString())
                        .withDetail("coveredUntil", TOMORROW.toString())
                        .build());
    }

    @Test
    void isDownBeforeTheFirstImport() {
        assertThat(health()).isEqualTo(Health.down().build());
    }

    private Health health() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new TimetableHealthIndicator(new ImportHistory(jdbc, clock), jdbc, clock).health();
    }

    private void recordImport(String outcome, Instant finishedAt) {
        var failed = outcome.equals("FAILED");
        jdbc.sql("""
                        INSERT INTO public.imports (started_at, finished_at, outcome, error, feed_url, row_counts)
                        VALUES (:finishedAt, :finishedAt, :outcome, :error, 'http://localhost/', CAST(:rowCounts AS jsonb))
                        """)
                .param("finishedAt", finishedAt.atOffset(ZoneOffset.UTC))
                .param("outcome", outcome)
                .param("error", failed ? "java.io.IOException: cut off" : null)
                .param("rowCounts", failed ? null : "{}")
                .update();
    }

    private void runsUntil(LocalDate lastDay) {
        runs(FIRST_DAY, lastDay);
    }

    private void runs(LocalDate firstDay, LocalDate lastDay) {
        jdbc.sql("""
                        INSERT INTO timetable.service_calendars
                        VALUES ('T0', true, true, true, true, true, true, true, :firstDay, :lastDay)
                        """).param("firstDay", firstDay).param("lastDay", lastDay).update();
    }

    private void addedOn(LocalDate day) {
        jdbc.sql("INSERT INTO timetable.calendar_dates VALUES ('T1', :date, 'ADDED')")
                .param("date", day)
                .update();
    }
}
