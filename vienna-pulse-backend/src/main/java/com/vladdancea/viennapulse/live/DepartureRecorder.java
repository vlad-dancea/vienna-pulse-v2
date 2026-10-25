package com.vladdancea.viennapulse.live;

import com.vladdancea.viennapulse.database.TimeLimited;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/// Keeps every round's live departures in `public.live_departures`, for statistics of how punctual
/// the lines are at the stations people open.
@Component
@Slf4j
@RequiredArgsConstructor
class DepartureRecorder {
    // The rows go to the database as one JSON array, whose keys are the table's column names.
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @TimeLimited
    private final JdbcClient jdbc;

    /// Adds the departures of `answered` that no round listed before and updates the others. A round
    /// publishes its answer once the departures requests waiting for it have it, so they never wait
    /// for the database. A write that fails is logged, since the board does not need it.
    @EventListener
    void record(RoundAnswered answered) {
        if (answered.departures().isEmpty()) {
            return;
        }
        var rows = answered.departures().stream().map(Row::of).toList();
        try {
            jdbc.sql("""
                            INSERT INTO public.live_departures AS recorded
                                (platform, line, direction, planned_time, station, live_time, countdown, first_seen,
                                 last_seen, trip_id, route_id)
                            -- One statement may not write a row twice, should an answer list a departure twice, and
                            -- only one of the two can pair with the timetable departure.
                            SELECT DISTINCT ON (platform, line, direction, planned_time)
                                   platform, line, direction, planned_time, station, live_time, countdown,
                                   CAST(:seen AS timestamptz), CAST(:seen AS timestamptz), trip_id, route_id
                            FROM jsonb_to_recordset(CAST(:departures AS jsonb)) AS seen (
                                platform integer, line text, direction text, planned_time timestamptz, station integer,
                                live_time timestamptz, countdown integer, trip_id text, route_id text)
                            ORDER BY platform, line, direction, planned_time, trip_id NULLS LAST
                            ON CONFLICT (platform, line, direction, planned_time) DO UPDATE
                            SET live_time = excluded.live_time,
                                countdown = excluded.countdown,
                                last_seen = excluded.last_seen,
                                -- A later timetable may not pair it, such as one read over an hour after it was planned.
                                trip_id = coalesce(excluded.trip_id, recorded.trip_id),
                                route_id = coalesce(excluded.route_id, recorded.route_id)
                            -- A write that took long must not undo the next round's.
                            WHERE recorded.last_seen <= excluded.last_seen
                            """)
                    .param("seen", answered.at().atOffset(ZoneOffset.UTC))
                    .param("departures", JSON.writeValueAsString(rows))
                    .update();
        } catch (RuntimeException exception) {
            log.warn(
                    "Could not record {} live departures: {}",
                    rows.size(),
                    NestedExceptionUtils.getMostSpecificCause(exception).toString());
        }
    }

    // A row of the table as the statement reads it from JSON.
    private record Row(
            int platform,
            String line,
            String direction,
            Instant plannedTime,
            int station,
            @Nullable Instant liveTime,
            int countdown,
            @Nullable String tripId,
            @Nullable String routeId) {
        static Row of(RoundAnswered.Seen seen) {
            var live = seen.live();
            var planned = seen.planned();
            return new Row(
                    live.platform(),
                    live.line(),
                    live.direction(),
                    live.planned(),
                    seen.station(),
                    live.real(),
                    live.countdown(),
                    planned == null ? null : planned.trip(),
                    planned == null ? null : planned.route());
        }
    }
}
