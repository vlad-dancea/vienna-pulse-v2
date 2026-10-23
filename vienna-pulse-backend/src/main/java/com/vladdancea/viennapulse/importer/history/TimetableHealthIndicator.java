package com.vladdancea.viennapulse.importer.history;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/// The `timetable` health component: DOWN when the last successful import is more than 36 hours
/// old or no service of the timetable runs tomorrow in Vienna, which would leave the map without
/// lines or vehicles. The readiness group leaves it out, because an old timetable still beats no API.
@Component
@RequiredArgsConstructor
public class TimetableHealthIndicator extends AbstractHealthIndicator {
    // The import runs every morning, so while it succeeds the last one is under a day old. The other
    // half day turns one failed morning DOWN by the evening.
    private static final Duration MAX_AGE = Duration.ofHours(36);
    // The time zone the timetable's days are written in.
    private static final ZoneId VIENNA = ZoneId.of("Europe/Vienna");

    private final ImportHistory history;
    private final JdbcClient jdbc;
    private final Clock clock;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        var now = clock.instant();
        var lastSuccess = history.lastSuccess();
        var coverage = coverage();
        var fresh =
                lastSuccess.filter(time -> !time.isBefore(now.minus(MAX_AGE))).isPresent();
        var running = runsOn(LocalDate.ofInstant(now, VIENNA).plusDays(1));

        if (fresh && running) {
            builder.up();
        } else {
            builder.down();
        }
        lastSuccess.ifPresent(time -> builder.withDetail("lastSuccessfulImport", time.toString()));
        if (coverage.first() != null && coverage.last() != null) {
            builder.withDetail("coveredFrom", coverage.first().toString())
                    .withDetail("coveredUntil", coverage.last().toString());
        }
    }

    // Whether any service runs on `day`, decided like the lines endpoint decides which lines to draw.
    private boolean runsOn(LocalDate day) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT service_id
                            FROM timetable.service_calendars
                            WHERE CAST(:day AS date) BETWEEN start_date AND end_date
                              AND CASE extract(isodow FROM CAST(:day AS date))
                                      WHEN 1 THEN monday
                                      WHEN 2 THEN tuesday
                                      WHEN 3 THEN wednesday
                                      WHEN 4 THEN thursday
                                      WHEN 5 THEN friday
                                      WHEN 6 THEN saturday
                                      WHEN 7 THEN sunday
                                  END
                            EXCEPT
                            SELECT service_id
                            FROM timetable.calendar_dates
                            WHERE date = :day AND exception_type = 'REMOVED'
                            UNION
                            SELECT service_id
                            FROM timetable.calendar_dates
                            WHERE date = :day AND exception_type = 'ADDED'
                        )
                        """).param("day", day).query(Boolean.class).single();
    }

    // The days the timetable covers, counted the way the lines endpoint counts them, which answers
    // 404 for any other day.
    private Coverage coverage() {
        return jdbc.sql("""
                        SELECT min(first_date) AS first_date, max(last_date) AS last_date
                        FROM (
                            SELECT start_date AS first_date, end_date AS last_date
                            FROM timetable.service_calendars
                            UNION ALL
                            SELECT date, date
                            FROM timetable.calendar_dates
                            WHERE exception_type = 'ADDED'
                        ) AS days
                        """)
                .query((row, number) -> new Coverage(
                        row.getObject("first_date", LocalDate.class), row.getObject("last_date", LocalDate.class)))
                .single();
    }

    // Both days are empty while the timetable is.
    private record Coverage(
            @Nullable LocalDate first, @Nullable LocalDate last) {}
}
