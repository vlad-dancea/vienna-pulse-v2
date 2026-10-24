package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.Departures.Departure;
import com.vladdancea.viennapulse.api.Departures.Line;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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
    private static final Duration HORIZON = Duration.ofHours(1);

    /// How many departures of each line and headsign the board shows.
    private static final int PER_LINE = 2;

    @ApiDatabase
    private final JdbcClient jdbc;

    /// The next two departures of each line and headsign in the hour after `now`, soonest first,
    /// from any platform of the stop whose merged id, like `at:49:1320`, the map shows. Fails with
    /// a 404 for an unknown stop.
    ///
    /// The board groups the departures by line and headsign, like the displays at the stops. A fixed
    /// number of the soonest departures would show one time for most groups at a busy stop, where
    /// frequent U-Bahn trains take most of them, and miss some trams and buses leaving within half
    /// an hour.
    ///
    /// A call is a departure when passengers can board and the trip goes on to another stop. Its
    /// time counts from noon minus 12 hours on its service day, which is midnight except on the
    /// days the clocks change, and passes 24 hours for trips that run after midnight. The feed's
    /// times end before 30 hours, so the service days of yesterday, today and tomorrow hold every
    /// departure in the next hour.
    ///
    /// The stop's name comes from the same statement as its departures, because an import can swap
    /// in the next timetable between two.
    public Departures departures(String stopId, Instant now) {
        var rows = jdbc.sql("""
                        WITH platforms AS (
                            SELECT id, name
                            FROM timetable.stops
                            WHERE merged_id = :stop
                        ),
                        -- Each service day with the seconds of its times that fall in the next hour.
                        service_days AS (
                            SELECT day,
                                   origin,
                                   extract(epoch FROM CAST(:now AS timestamptz) - origin) AS first_second,
                                   extract(epoch FROM CAST(:until AS timestamptz) - origin) AS end_second
                            FROM (SELECT CAST(:today AS date) + shift AS day,
                                         pulse.service_day_origin(CAST(:today AS date) + shift) AS origin
                                  FROM generate_series(-1, 1) AS shift) AS days
                        ),
                        running AS (
                            SELECT d.day, r.service_id
                            FROM service_days d CROSS JOIN LATERAL pulse.running_services(d.day) AS r
                        ),
                        -- The stop's calls come from the stop_id index first. Left to itself, the planner, which
                        -- expects about one running service where there are hundreds, reads every call of every
                        -- running trip instead, about 700,000 rows.
                        calls AS MATERIALIZED (
                            SELECT trip_id, sequence, stop_id, departure_time
                            FROM timetable.stop_times
                            WHERE stop_id IN (SELECT id FROM platforms) AND pickup_type <> 'NONE'
                        ),
                        -- The calls in the next hour, about 4,000 at a busy stop, look up their trips one at a
                        -- time, day by day. Joined for all days at once, the planner, expecting a few running
                        -- services, pairs every call of the stop with every running service, millions of rows.
                        departures AS (
                            SELECT next.*,
                                   row_number() OVER (PARTITION BY next.route_id, next.headsign
                                                      ORDER BY next.time, next.trip_id, next.sequence) AS rank
                            FROM service_days d
                            CROSS JOIN LATERAL (
                                SELECT d.origin + c.departure_time * interval '1 second' AS time,
                                       c.trip_id,
                                       c.sequence,
                                       c.stop_id,
                                       t.route_id,
                                       t.headsign
                                FROM calls c
                                JOIN timetable.trips t ON t.id = c.trip_id
                                WHERE c.departure_time >= d.first_second AND c.departure_time < d.end_second
                                  AND t.service_id IN (SELECT service_id FROM running r WHERE r.day = d.day)
                                  -- The last call of a trip only arrives, though the feed lets passengers board.
                                  AND EXISTS (SELECT
                                              FROM timetable.stop_times later
                                              WHERE later.trip_id = c.trip_id AND later.sequence > c.sequence)
                            ) AS next
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
                        LEFT JOIN (SELECT * FROM departures WHERE rank <= :perLine) AS next ON true
                        LEFT JOIN timetable.routes r ON r.id = next.route_id
                        ORDER BY next.time, next.trip_id, next.sequence
                        """)
                .param("stop", stopId)
                .param("today", LocalDate.ofInstant(now, Timetable.ZONE))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("until", now.plus(HORIZON).atOffset(ZoneOffset.UTC))
                .param("perLine", PER_LINE)
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
