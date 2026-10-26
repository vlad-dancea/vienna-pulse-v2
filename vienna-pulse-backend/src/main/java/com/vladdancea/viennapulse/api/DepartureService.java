package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.Departures.Departure;
import com.vladdancea.viennapulse.api.Departures.Line;
import com.vladdancea.viennapulse.database.TimeLimited;
import com.vladdancea.viennapulse.live.LiveDepartures;
import com.vladdancea.viennapulse.live.PlannedDeparture;
import com.vladdancea.viennapulse.live.Platforms;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/// Reads when trips leave a stop in the current timetable, and adds Wiener Linien's live times.
@Service
@RequiredArgsConstructor
public class DepartureService {
    private static final Duration HORIZON = Duration.ofHours(1);

    /// How long before now a departure may be planned and stay on the board, while Wiener Linien
    /// expect it later. During a U3 disruption on 2026-10-24, they expected a train 47 minutes late.
    private static final Duration LATE = Duration.ofHours(1);

    /// How many departures of each line and headsign the board shows.
    private static final int PER_LINE = 2;

    @TimeLimited
    private final JdbcClient jdbc;

    private final LiveDepartures liveDepartures;

    private final Platforms platforms;

    /// The next two departures of each line and headsign, soonest first, from any platform of the
    /// stop whose merged id, like `at:49:1320`, the map shows, and whether Wiener Linien's live times
    /// for them have been fetched. Fails with a 404 for an unknown stop. When the stop's station has no
    /// live times yet and the fair-use rules allow a request, it asks Wiener Linien while it reads the
    /// timetable, and waits for their answer at most until that request times out.
    ///
    /// A departure leaves at its live time where Wiener Linien have one, and at its planned time
    /// otherwise, which sets the order and decides which two of each line and headsign the board
    /// shows. The board has the departures planned in the hour after `now`, and those planned in the
    /// hour before that are still expected to leave.
    ///
    /// The board groups the departures by line and headsign, like the displays at the stops. A fixed
    /// number of the soonest departures would show one time for most groups at a busy stop, where
    /// frequent U-Bahn trains take most of them, and miss some trams and buses leaving within half
    /// an hour.
    ///
    /// A departure has the code that signs show for its platform, such as `1` or `C`, when the live
    /// departure paired with it leaves from a platform that Wiener Linien's list of platform codes
    /// has. The timetable's platforms have no codes, and their ids have nothing in common with Wiener
    /// Linien's platform numbers, so a departure without a live one has no code either.
    ///
    /// A call is a departure when passengers can board and the trip goes on to another stop. Its
    /// time counts from noon minus 12 hours on its service day, which is midnight except on the
    /// days the clocks change, and passes 24 hours for trips that run after midnight. The feed's
    /// times end before 30 hours, so the service days of yesterday, today and tomorrow hold every
    /// departure in the hours before and after `now`.
    ///
    /// The stop's name comes from the same statement as its departures, because an import can swap
    /// in the next timetable between two.
    public Departures departures(String stopId, Instant now) {
        // Wiener Linien usually answer before the database does, so a round this starts costs the
        // answer nothing.
        liveDepartures.open(stopId, now);
        var rows = jdbc.sql("""
                        WITH platforms AS (
                            SELECT id, name
                            FROM timetable.stops
                            WHERE merged_id = :stop
                        ),
                        -- Each service day with the seconds of its times that fall in the hours before and after now.
                        -- The times are whole seconds, so rounding the bounds up keeps the same calls, and integer
                        -- bounds let the index on stop and departure time find them.
                        service_days AS (
                            SELECT day,
                                   origin,
                                   CAST(ceil(extract(epoch FROM CAST(:since AS timestamptz) - origin)) AS integer)
                                       AS first_second,
                                   CAST(ceil(extract(epoch FROM CAST(:until AS timestamptz) - origin)) AS integer)
                                       AS end_second
                            FROM (SELECT CAST(:today AS date) + shift AS day,
                                         pulse.service_day_origin(CAST(:today AS date) + shift) AS origin
                                  FROM generate_series(-1, 1) AS shift) AS days
                        ),
                        running AS (
                            SELECT d.day, r.service_id
                            FROM service_days d CROSS JOIN LATERAL pulse.running_services(d.day) AS r
                        ),
                        -- The stop's calls in those hours, one range of the index per platform and service day,
                        -- rather than all of them, which lie on a third of the table's pages since it is stored trip
                        -- by trip. Materialized, they are read before any trip. The planner expects about one
                        -- running service where there are hundreds, and could start from every running trip's calls.
                        calls AS MATERIALIZED (
                            SELECT d.day, s.trip_id, s.sequence, s.stop_id, s.departure_time
                            FROM service_days d
                            CROSS JOIN platforms p
                            JOIN timetable.stop_times s
                              ON s.stop_id = p.id
                             AND s.departure_time >= d.first_second AND s.departure_time < d.end_second
                            WHERE s.pickup_type <> 'NONE'
                        ),
                        -- The calls look up their trips one at a time, day by day. Joined for all days at once, the
                        -- planner, expecting a few running services, pairs every call of the stop with every
                        -- running service, millions of rows.
                        departures AS (
                            SELECT next.*
                            FROM service_days d
                            CROSS JOIN LATERAL (
                                SELECT d.origin + c.departure_time * interval '1 second' AS time,
                                       c.trip_id,
                                       c.sequence,
                                       c.stop_id,
                                       t.route_id,
                                       t.headsign,
                                       t.direction_id
                                FROM calls c
                                JOIN timetable.trips t ON t.id = c.trip_id
                                WHERE c.day = d.day
                                  AND t.service_id IN (SELECT service_id FROM running r WHERE r.day = d.day)
                                -- Most trips at a stop do not run on a given day. The fence drops them before the
                                -- check below, which reads the large primary key of the stop times once per call.
                                OFFSET 0
                            ) AS next
                            -- The last call of a trip only arrives, though the feed lets passengers board.
                            WHERE EXISTS (SELECT
                                          FROM timetable.stop_times later
                                          WHERE later.trip_id = next.trip_id AND later.sequence > next.sequence)
                        )
                        SELECT stop.name AS stop_name,
                               next.time,
                               next.trip_id,
                               next.route_id,
                               next.headsign,
                               next.direction_id,
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
                .param("since", now.minus(LATE).atOffset(ZoneOffset.UTC))
                .param("until", now.plus(HORIZON).atOffset(ZoneOffset.UTC))
                .query((row, number) -> new Row(row.getString("stop_name"), planned(row)))
                .list();
        var name = rows.getFirst().stopName();
        if (name == null) {
            throw Problems.notFound("There is no stop '%s'".formatted(stopId));
        }
        var planned = rows.stream().map(Row::planned).filter(Objects::nonNull).toList();
        var timetable = planned.stream()
                .map(departure -> new PlannedDeparture(
                        departure.trip(),
                        departure.line().id(),
                        departure.line().properties().name(),
                        departure.direction(),
                        departure.time().toInstant()))
                .toList();
        var live = liveDepartures.await(stopId, now, timetable);
        var paired = live.pair(timetable);
        var leaving = new ArrayList<Departure>();
        for (var i = 0; i < planned.size(); i++) {
            var liveDeparture = paired.get(i);
            var liveTime = liveDeparture == null ? null : liveDeparture.real();
            var departure = planned.get(i)
                    .withLiveData(
                            liveTime == null ? null : vienna(liveTime),
                            liveDeparture == null ? null : platforms.code(liveDeparture.platform()));
            if (!leaves(departure).toInstant().isBefore(now)) {
                leaving.add(departure);
            }
        }
        // A stable sort, so departures that leave at the same time keep the statement's order.
        leaving.sort(Comparator.comparing(DepartureService::leaves));
        var board = new ArrayList<Departure>();
        var shown = new HashMap<Group, Integer>();
        for (var departure : leaving) {
            if (shown.merge(new Group(departure.line().id(), departure.headsign()), 1, Integer::sum) <= PER_LINE) {
                board.add(departure);
            }
        }
        return new Departures(new Departures.Stop(stopId, name), live.status(), live.liveIn(), board);
    }

    private static OffsetDateTime leaves(Departure departure) {
        return departure.liveTime() != null ? departure.liveTime() : departure.plannedTime();
    }

    private static OffsetDateTime vienna(Instant time) {
        return time.atZone(Timetable.ZONE).toOffsetDateTime();
    }

    private static @Nullable Planned planned(ResultSet row) throws SQLException {
        var time = row.getObject("time", OffsetDateTime.class);
        if (time == null) {
            return null;
        }
        return new Planned(
                time.atZoneSameInstant(Timetable.ZONE).toOffsetDateTime(),
                row.getString("trip_id"),
                new Line(row.getString("route_id"), LineProperties.from(row)),
                row.getString("headsign"),
                row.getString("stop_id"),
                row.getObject("direction_id", Integer.class));
    }

    // A departure in the timetable, with the id and direction id of its trip, which only the live times
    // need.
    private record Planned(
            OffsetDateTime time,
            String trip,
            Line line,
            @Nullable String headsign,
            String platform,
            @Nullable Integer direction) {
        Departure withLiveData(@Nullable OffsetDateTime liveTime, @Nullable String platformCode) {
            return new Departure(time, liveTime, line, headsign, platform, platformCode);
        }
    }

    // A line and headsign, which the board shows as one row.
    private record Group(String line, @Nullable String headsign) {}

    // The stop's name, null when it does not exist, and one departure, null when none is in the window.
    private record Row(@Nullable String stopName, @Nullable Planned planned) {}
}
