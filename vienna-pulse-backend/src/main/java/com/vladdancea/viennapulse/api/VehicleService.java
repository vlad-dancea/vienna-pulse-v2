package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.Itinerary.Knot;
import com.vladdancea.viennapulse.api.Itinerary.ShapePoint;
import com.vladdancea.viennapulse.api.geojson.Feature;
import com.vladdancea.viennapulse.api.geojson.Point;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/// Works out where the vehicles of the current timetable are and where they go next.
@Service
@RequiredArgsConstructor
public class VehicleService {
    /// How far ahead each vehicle's path goes: twice the time between two requests of a map, so the
    /// vehicles keep moving when one answer fails or comes late.
    static final Duration PATH = Duration.ofSeconds(30);

    @ApiDatabase
    private final JdbcClient jdbc;

    /// A point for every trip that has left its first stop and not yet reached its last at `now`,
    /// ordered by trip id, where its timetable puts it, with the path it follows for the next
    /// [#PATH]. Trips without a shape, or without distances along it, are left out, since there is
    /// nowhere to put them.
    ///
    /// Between leaving one stop and reaching the next, a vehicle covers the distance between them
    /// along its shape at an even speed. It waits at a stop from its arrival until its departure.
    /// A call without times, which the feed allows between two timed ones, is passed like any other
    /// point of the shape, and points of the shape without a distance are passed over. When two calls
    /// share a minute, the vehicle is halfway between them at that minute instead of jumping.
    ///
    /// Times count from noon minus 12 hours on their service day, as for the departures, and pass
    /// 24 hours for trips that run after midnight. The feed's times end before 30 hours, so trips of
    /// yesterday's service day can still be running. So can tomorrow's on the evening before the
    /// clocks go forward, when its time 00:00 is 23:00 the day before.
    public Vehicles vehicles(Instant now) {
        var features = jdbc.sql("""
                        WITH days AS (
                            SELECT CAST(:today AS date) + shift AS day,
                                   pulse.service_day_origin(CAST(:today AS date) + shift) AS origin
                            FROM generate_series(-1, 1) AS shift
                        ),
                        -- Each service day with the second of its times that is now.
                        moments AS (
                            SELECT day, extract(epoch FROM CAST(:now AS timestamptz) - origin) AS second
                            FROM days
                        ),
                        running AS (
                            SELECT m.day, r.service_id
                            FROM moments m CROSS JOIN LATERAL pulse.running_services(m.day) AS r
                        ),
                        -- The spans find the running trips without reading the calls of the tens of thousands
                        -- that run on the day but not now.
                        active AS (
                            SELECT s.trip_id, m.second
                            FROM moments m
                            JOIN timetable.trip_spans s
                              ON s.first_departure <= m.second AND s.last_arrival > m.second
                            WHERE s.service_id IN (SELECT service_id FROM running r WHERE r.day = m.day)
                        ),
                        -- The calls with times from the last stop each trip left to the first it reaches after
                        -- the path ends, or its last, and the calls before and after them that share their
                        -- minutes. The first call has only a departure and the last only an arrival, so each
                        -- time stands in for the other.
                        calls AS (
                            SELECT a.trip_id, a.second, t.route_id, t.headsign, t.shape_id, c.*
                            FROM active a
                            JOIN timetable.trips t ON t.id = a.trip_id
                            CROSS JOIN LATERAL (
                                SELECT sequence, departure_time
                                FROM timetable.stop_times
                                WHERE trip_id = a.trip_id AND departure_time <= a.second
                                ORDER BY sequence DESC
                                LIMIT 1
                            ) AS left_stop
                            LEFT JOIN LATERAL (
                                SELECT sequence
                                FROM timetable.stop_times
                                WHERE trip_id = a.trip_id AND sequence < left_stop.sequence
                                  AND departure_time < left_stop.departure_time
                                ORDER BY sequence DESC
                                LIMIT 1
                            ) AS earlier_stop ON true
                            LEFT JOIN LATERAL (
                                SELECT sequence, arrival_time
                                FROM timetable.stop_times
                                WHERE trip_id = a.trip_id AND sequence > left_stop.sequence
                                  AND arrival_time >= a.second + :path
                                ORDER BY sequence
                                LIMIT 1
                            ) AS last_stop ON true
                            LEFT JOIN LATERAL (
                                SELECT sequence
                                FROM timetable.stop_times
                                WHERE trip_id = a.trip_id AND sequence > last_stop.sequence
                                  AND arrival_time > last_stop.arrival_time
                                ORDER BY sequence
                                LIMIT 1
                            ) AS later_stop ON true
                            CROSS JOIN LATERAL (
                                SELECT array_agg(coalesce(arrival_time, departure_time) ORDER BY sequence) AS arrivals,
                                       array_agg(coalesce(departure_time, arrival_time) ORDER BY sequence) AS departures,
                                       array_agg(distance_traveled ORDER BY sequence) AS distances,
                                       min(distance_traveled) AS first_distance,
                                       max(distance_traveled) AS last_distance
                                FROM timetable.stop_times
                                WHERE trip_id = a.trip_id
                                  AND sequence >= coalesce(earlier_stop.sequence, left_stop.sequence)
                                  AND sequence <= coalesce(later_stop.sequence, 2147483647)
                                  AND (arrival_time IS NOT NULL OR departure_time IS NOT NULL)
                                  AND distance_traveled IS NOT NULL
                            ) AS c
                        ),
                        -- The points of the shape from the last one at or before the first call to the first
                        -- one at or after the last call, which the vehicle cannot leave until the path ends.
                        shaped AS (
                            SELECT c.*, p.*
                            FROM calls c
                            -- Reading the shape backwards from its end takes half a shape on average, which an
                            -- index on the distance would only make 20 ms faster for the peak hour's 1,000 trips.
                            CROSS JOIN LATERAL (
                                SELECT sequence
                                FROM timetable.shape_points
                                WHERE shape_id = c.shape_id AND distance_traveled <= c.first_distance
                                ORDER BY sequence DESC
                                LIMIT 1
                            ) AS first_point
                            LEFT JOIN LATERAL (
                                SELECT sequence
                                FROM timetable.shape_points
                                WHERE shape_id = c.shape_id AND sequence > first_point.sequence
                                  AND distance_traveled >= c.last_distance
                                ORDER BY sequence
                                LIMIT 1
                            ) AS last_point ON true
                            CROSS JOIN LATERAL (
                                SELECT array_agg(longitude ORDER BY sequence) AS longitudes,
                                       array_agg(latitude ORDER BY sequence) AS latitudes,
                                       array_agg(distance_traveled ORDER BY sequence) AS point_distances
                                FROM timetable.shape_points
                                WHERE shape_id = c.shape_id
                                  AND sequence >= first_point.sequence
                                  AND (last_point.sequence IS NULL OR sequence <= last_point.sequence)
                                  AND distance_traveled IS NOT NULL
                            ) AS p
                        )
                        SELECT s.trip_id,
                               s.route_id,
                               s.headsign,
                               s.second,
                               s.arrivals,
                               s.departures,
                               s.distances,
                               s.longitudes,
                               s.latitudes,
                               s.point_distances,
                               r.short_name,
                               r.long_name,
                               r.type,
                               r.color,
                               r.text_color
                        FROM shaped s
                        JOIN timetable.routes r ON r.id = s.route_id
                        ORDER BY s.trip_id
                        """)
                .param("today", LocalDate.ofInstant(now, Timetable.ZONE))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("path", PATH.toSeconds())
                .query((row, number) -> {
                    var second = row.getDouble("second");
                    var path = itinerary(row).path(second, second + PATH.toSeconds());
                    var start = path.getFirst();
                    return new Feature<>(
                            row.getString("trip_id"),
                            new Point(start.longitude(), start.latitude()),
                            new VehicleProperties(
                                    row.getString("route_id"),
                                    LineProperties.from(row),
                                    row.getString("headsign"),
                                    path));
                })
                .list();
        return new Vehicles(now, features);
    }

    // A call with an arrival and a departure is two knots, unless the vehicle does not wait.
    private static Itinerary itinerary(ResultSet row) throws SQLException {
        var arrivals = (Integer[]) row.getArray("arrivals").getArray();
        var departures = (Integer[]) row.getArray("departures").getArray();
        var distances = (Double[]) row.getArray("distances").getArray();
        var knots = new ArrayList<Knot>();
        for (var call = 0; call < distances.length; call++) {
            knots.add(new Knot(arrivals[call], distances[call]));
            if (!departures[call].equals(arrivals[call])) {
                knots.add(new Knot(departures[call], distances[call]));
            }
        }
        var longitudes = (Double[]) row.getArray("longitudes").getArray();
        var latitudes = (Double[]) row.getArray("latitudes").getArray();
        var pointDistances = (Double[]) row.getArray("point_distances").getArray();
        var shape = new ArrayList<ShapePoint>();
        for (var point = 0; point < pointDistances.length; point++) {
            shape.add(new ShapePoint(longitudes[point], latitudes[point], pointDistances[point]));
        }
        return new Itinerary(knots, shape);
    }
}
