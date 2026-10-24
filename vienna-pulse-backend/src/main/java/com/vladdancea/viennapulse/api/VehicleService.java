package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.Feature;
import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import com.vladdancea.viennapulse.api.geojson.Point;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/// Works out where the vehicles of the current timetable are.
@Service
@RequiredArgsConstructor
public class VehicleService {
    @ApiDatabase
    private final JdbcClient jdbc;

    /// A point for every trip that has left its first stop and not yet reached its last at `now`,
    /// ordered by trip id, where its timetable puts it. Trips without a shape, or without distances
    /// along it, are left out, since there is nowhere to put them.
    ///
    /// Between leaving one stop and reaching the next, a vehicle covers the distance between them
    /// along its shape at an even speed. It waits at a stop from its arrival until its departure.
    /// A call without times, which the feed allows between two timed ones, is passed like any other
    /// point of the shape. The point is then placed on the straight line between the two points of
    /// the shape that the distance falls between.
    ///
    /// Times count from noon minus 12 hours on their service day, as for the departures, and pass
    /// 24 hours for trips that run after midnight. The feed's times end before 30 hours, so trips of
    /// yesterday's service day can still be running. So can tomorrow's on the evening before the
    /// clocks go forward, when its time 00:00 is 23:00 the day before.
    public FeatureCollection<VehicleProperties> vehicles(Instant now) {
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
                        -- How far along its shape each trip is: the last stop it left and the next one with a
                        -- time, and the share of the time between them that has passed, which is all of it
                        -- while the vehicle waits at the next stop.
                        along AS (
                            SELECT a.trip_id,
                                   t.route_id,
                                   t.headsign,
                                   t.shape_id,
                                   left_stop.distance_traveled
                                       + least(1, (a.second - left_stop.departure_time)
                                                  / greatest(next_stop.arrival_time - left_stop.departure_time, 1))
                                       * (next_stop.distance_traveled - left_stop.distance_traveled) AS distance
                            FROM active a
                            JOIN timetable.trips t ON t.id = a.trip_id
                            CROSS JOIN LATERAL (
                                SELECT sequence, departure_time, distance_traveled
                                FROM timetable.stop_times
                                WHERE trip_id = a.trip_id AND departure_time <= a.second
                                ORDER BY sequence DESC
                                LIMIT 1
                            ) AS left_stop
                            CROSS JOIN LATERAL (
                                SELECT arrival_time, distance_traveled
                                FROM timetable.stop_times
                                WHERE trip_id = a.trip_id AND sequence > left_stop.sequence AND arrival_time IS NOT NULL
                                ORDER BY sequence
                                LIMIT 1
                            ) AS next_stop
                        ),
                        -- The two points of the shape the distance falls between, and how far along from the
                        -- first to the second it is. Points without a distance are passed over like calls
                        -- without times, and at the end of the shape there is no second point.
                        placed AS (
                            SELECT g.*,
                                   before.longitude AS longitude_before,
                                   before.latitude AS latitude_before,
                                   coalesce(after.longitude, before.longitude) AS longitude_after,
                                   coalesce(after.latitude, before.latitude) AS latitude_after,
                                   CASE WHEN after.distance_traveled > before.distance_traveled
                                        THEN (g.distance - before.distance_traveled)
                                             / (after.distance_traveled - before.distance_traveled)
                                        ELSE 0
                                   END AS share
                            FROM along g
                            -- Reading the shape backwards from its end takes half a shape on average, which an
                            -- index on the distance would only make 20 ms faster for the peak hour's 1,000 trips.
                            CROSS JOIN LATERAL (
                                SELECT sequence, latitude, longitude, distance_traveled
                                FROM timetable.shape_points
                                WHERE shape_id = g.shape_id AND distance_traveled <= g.distance
                                ORDER BY sequence DESC
                                LIMIT 1
                            ) AS before
                            LEFT JOIN LATERAL (
                                SELECT latitude, longitude, distance_traveled
                                FROM timetable.shape_points
                                WHERE shape_id = g.shape_id AND sequence > before.sequence
                                  AND distance_traveled IS NOT NULL
                                ORDER BY sequence
                                LIMIT 1
                            ) AS after ON true
                        )
                        SELECT v.trip_id,
                               v.route_id,
                               v.headsign,
                               r.short_name,
                               r.long_name,
                               r.type,
                               r.color,
                               r.text_color,
                               -- Rounded like the lines, to six decimals (about 10 cm).
                               round((v.longitude_before + (v.longitude_after - v.longitude_before) * v.share) * 1e6)
                                   / 1e6 AS longitude,
                               round((v.latitude_before + (v.latitude_after - v.latitude_before) * v.share) * 1e6)
                                   / 1e6 AS latitude
                        FROM placed v
                        JOIN timetable.routes r ON r.id = v.route_id
                        ORDER BY v.trip_id
                        """)
                .param("today", LocalDate.ofInstant(now, Timetable.ZONE))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query((row, number) -> new Feature<>(
                        row.getString("trip_id"),
                        new Point(row.getDouble("longitude"), row.getDouble("latitude")),
                        new VehicleProperties(
                                row.getString("route_id"), LineProperties.from(row), row.getString("headsign"))))
                .list();
        return new FeatureCollection<>(features);
    }
}
