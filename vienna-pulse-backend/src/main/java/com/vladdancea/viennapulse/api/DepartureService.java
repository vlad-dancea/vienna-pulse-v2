package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.Departures.Departure;
import com.vladdancea.viennapulse.api.Departures.Line;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/// Reads when trips leave a stop in the current timetable.
@Service
@RequiredArgsConstructor
public class DepartureService {
    private static final int LIMIT = 20;

    private final JdbcClient jdbc;

    /// The first 20 departures at or after `now` from any platform of the stop whose merged id, like
    /// `at:49:1320`, the map shows. Fails with a 404 for an unknown stop.
    ///
    /// A call is a departure when passengers can board and the trip goes on to another stop. Its
    /// time counts from noon minus 12 hours on its service day, which is midnight except on the
    /// days the clocks change, and passes 24 hours for trips that run after midnight. The feed's
    /// times end before 30 hours, so the service days of yesterday, today and tomorrow hold every
    /// departure until tomorrow's service day ends, where the search stops: a later day's earliest
    /// trips would otherwise be missing from between the ones of tomorrow.
    ///
    /// The stop's name comes from the same statement as its departures, because an import can swap
    /// in the next timetable between two.
    public Departures departures(String stopId, Instant now) {
        var rows = jdbc.sql("""
                        WITH platforms AS (
                            SELECT id, name
                            FROM timetable.stops
                            -- The space keeps :3 from reading as a parameter.
                            WHERE array_to_string((string_to_array(id, ':'))[1 : 3], ':') = :stop
                        ),
                        days AS (
                            SELECT shift,
                                   CAST(:today AS date) + shift AS day,
                                   (CAST(:today AS date) + shift + time '12:00') AT TIME ZONE 'Europe/Vienna'
                                       - interval '12 hours' AS origin
                            FROM generate_series(-1, 2) AS shift
                        ),
                        -- Each service day with the seconds of its times that fall between now and the end of
                        -- tomorrow's service day.
                        service_days AS (
                            SELECT day,
                                   origin,
                                   extract(epoch FROM CAST(:now AS timestamptz) - origin) AS first_second,
                                   extract(epoch FROM (SELECT origin FROM days WHERE shift = 2) - origin) AS end_second
                            FROM days
                            WHERE shift <= 1
                        ),
                        running AS (
                            (SELECT d.day, c.service_id
                             FROM service_days d
                             JOIN timetable.service_calendars c
                               ON d.day BETWEEN c.start_date AND c.end_date
                              AND CASE extract(isodow FROM d.day)
                                      WHEN 1 THEN c.monday
                                      WHEN 2 THEN c.tuesday
                                      WHEN 3 THEN c.wednesday
                                      WHEN 4 THEN c.thursday
                                      WHEN 5 THEN c.friday
                                      WHEN 6 THEN c.saturday
                                      WHEN 7 THEN c.sunday
                                  END
                             EXCEPT
                             SELECT date, service_id
                             FROM timetable.calendar_dates
                             WHERE exception_type = 'REMOVED' AND date IN (SELECT day FROM service_days))
                            UNION
                            SELECT date, service_id
                            FROM timetable.calendar_dates
                            WHERE exception_type = 'ADDED' AND date IN (SELECT day FROM service_days)
                        ),
                        -- The stop's calls come from the stop_id index first. Left to itself, the planner, which
                        -- expects about one running service where there are hundreds, reads every call of every
                        -- running trip instead, about 700,000 rows.
                        calls AS MATERIALIZED (
                            SELECT trip_id, sequence, stop_id, departure_time
                            FROM timetable.stop_times
                            WHERE stop_id IN (SELECT id FROM platforms) AND pickup_type <> 'NONE'
                        ),
                        -- Each day's calls are sorted by time before anything else, so the trips are looked up
                        -- one call at a time until 20 of them run that day and go on, instead of for all of the
                        -- tens of thousands of calls a busy stop has.
                        departures AS (
                            SELECT next.*
                            FROM service_days d
                            CROSS JOIN LATERAL (
                                SELECT d.origin + c.departure_time * interval '1 second' AS time,
                                       c.trip_id,
                                       c.sequence,
                                       c.stop_id,
                                       t.route_id,
                                       t.headsign
                                -- OFFSET 0 keeps the sort in here, ahead of the joins.
                                FROM (SELECT *
                                      FROM calls
                                      WHERE departure_time >= d.first_second AND departure_time < d.end_second
                                      ORDER BY departure_time, trip_id, sequence
                                      OFFSET 0) AS c
                                JOIN timetable.trips t ON t.id = c.trip_id
                                WHERE t.service_id IN (SELECT service_id FROM running r WHERE r.day = d.day)
                                  -- The last call of a trip only arrives, though the feed lets passengers board.
                                  AND EXISTS (SELECT
                                              FROM timetable.stop_times later
                                              WHERE later.trip_id = c.trip_id AND later.sequence > c.sequence)
                                ORDER BY c.departure_time, c.trip_id, c.sequence
                                LIMIT :limit
                            ) AS next
                            ORDER BY next.time, next.trip_id, next.sequence
                            LIMIT :limit
                        )
                        SELECT stop.name AS stop_name,
                               next.time,
                               next.route_id,
                               next.headsign,
                               next.stop_id,
                               r.short_name,
                               r.long_name,
                               r.type,
                               r.color,
                               r.text_color
                        -- The name comes in every row, and alone in one when nothing leaves; it is null
                        -- only when no platform belongs to the stop.
                        FROM (SELECT mode() WITHIN GROUP (ORDER BY name) AS name FROM platforms) AS stop
                        LEFT JOIN departures AS next ON true
                        LEFT JOIN timetable.routes r ON r.id = next.route_id
                        ORDER BY next.time, next.trip_id, next.sequence
                        """)
                .param("stop", stopId)
                .param("today", LocalDate.ofInstant(now, Timetable.ZONE))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("limit", LIMIT)
                .query((row, number) -> new Row(row.getString("stop_name"), departure(row)))
                .list();
        var name = rows.getFirst().stopName();
        if (name == null) {
            throw Problems.notFound("There is no stop '%s'".formatted(stopId));
        }
        return new Departures(
                new Departures.Stop(stopId, name),
                rows.stream().map(Row::departure).filter(Objects::nonNull).toList());
    }

    private static @Nullable Departure departure(ResultSet row) throws SQLException {
        var time = row.getObject("time", OffsetDateTime.class);
        if (time == null) {
            return null;
        }
        return new Departure(
                time.atZoneSameInstant(Timetable.ZONE).toOffsetDateTime(),
                new Line(row.getString("route_id"), LineProperties.from(row)),
                row.getString("headsign"),
                row.getString("stop_id"));
    }

    // The stop's name, null when it does not exist, and one departure, null when none is left.
    private record Row(@Nullable String stopName, @Nullable Departure departure) {}
}
