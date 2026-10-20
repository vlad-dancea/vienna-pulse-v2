package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.Feature;
import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import com.vladdancea.viennapulse.api.geojson.MultiLineString;
import com.vladdancea.viennapulse.api.geojson.Position;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

/// Reads the lines that run on a day of the current timetable.
@Service
@RequiredArgsConstructor
public class LineService {
    private final JdbcClient jdbc;

    /// Every route with a trip on `date`, ordered by id, drawn along the distinct paths its trips
    /// follow that day. Fails with a 404 [ErrorResponseException] if the timetable does not cover
    /// `date`, which an empty map would hide when the imports stop.
    ///
    /// A trip runs on the days of its service: the weekly days of its `calendar.txt` row between
    /// its first and last date, minus the days `calendar_dates.txt` removes, plus the days it adds.
    /// Many shapes of a route repeat the same points under another id, so the paths are compared by
    /// their points, rounded to six decimals (about 10 cm), and each is sent once. The paths keep the
    /// order of the first shape that follows them, so the response, and its ETag, stay the same for
    /// as long as the timetable does. A route whose trips follow no shape of two points or more
    /// keeps its feature with an empty MultiLineString.
    ///
    /// The days the timetable covers come from the same statement as the lines: a second one could
    /// read the next timetable, which an import can swap in between the two.
    public FeatureCollection<LineProperties> lines(LocalDate date) {
        var rows = jdbc.sql("""
                        WITH coverage AS (
                            SELECT min(first_date) AS first_date, max(last_date) AS last_date
                            FROM (
                                SELECT start_date AS first_date, end_date AS last_date
                                FROM timetable.service_calendars
                                UNION ALL
                                SELECT date, date
                                FROM timetable.calendar_dates
                                WHERE exception_type = 'ADDED'
                            ) AS days
                        ),
                        services AS (
                            (SELECT service_id
                             FROM timetable.service_calendars
                             WHERE CAST(:date AS date) BETWEEN start_date AND end_date
                               AND CASE extract(isodow FROM CAST(:date AS date))
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
                             WHERE date = :date AND exception_type = 'REMOVED')
                            UNION
                            SELECT service_id
                            FROM timetable.calendar_dates
                            WHERE date = :date AND exception_type = 'ADDED'
                        ),
                        route_shapes AS (
                            SELECT DISTINCT route_id, shape_id
                            FROM timetable.trips
                            WHERE service_id IN (SELECT service_id FROM services)
                        ),
                        -- One index lookup per shape: joining scans all of the points instead.
                        -- Rounding in double precision instead of numeric halves the time, and only
                        -- changes the result by a millionth of a degree on exact ties.
                        shape_paths AS (
                            SELECT shapes.shape_id, path.longitudes, path.latitudes
                            FROM (SELECT DISTINCT shape_id FROM route_shapes) AS shapes
                            CROSS JOIN LATERAL (
                                SELECT array_agg(round(longitude * 1e6) / 1e6 ORDER BY sequence) AS longitudes,
                                       array_agg(round(latitude * 1e6) / 1e6 ORDER BY sequence) AS latitudes,
                                       count(*) AS points
                                FROM timetable.shape_points
                                WHERE shape_id = shapes.shape_id
                            ) AS path
                            -- A line needs two points, and a shape of one would make the GeoJSON invalid.
                            WHERE path.points >= 2
                        ),
                        route_paths AS (
                            SELECT route_id, min(shape_id) AS first_shape_id, longitudes, latitudes
                            FROM route_shapes JOIN shape_paths USING (shape_id)
                            GROUP BY route_id, longitudes, latitudes
                        )
                        SELECT coverage.first_date,
                               coverage.last_date,
                               line.id,
                               line.name,
                               line.long_name,
                               line.type,
                               line.color,
                               line.text_color,
                               line.longitudes,
                               line.latitudes
                        -- The days covered come in every row, and alone in one when no line runs.
                        FROM coverage
                        LEFT JOIN (
                            SELECT r.id,
                                   coalesce(r.short_name, r.long_name) AS name,
                                   r.long_name,
                                   lower(r.type) AS type,
                                   '#' || r.color AS color,
                                   '#' || r.text_color AS text_color,
                                   p.first_shape_id,
                                   p.longitudes,
                                   p.latitudes
                            FROM (SELECT DISTINCT route_id FROM route_shapes) AS running
                            JOIN timetable.routes r ON r.id = running.route_id
                            LEFT JOIN route_paths p ON p.route_id = r.id
                        ) AS line ON true
                        ORDER BY line.id, line.first_shape_id
                        """)
                .param("date", date)
                .query((row, number) -> new Row(
                        row.getObject("first_date", LocalDate.class),
                        row.getObject("last_date", LocalDate.class),
                        row.getString("id") == null ? null : routePath(row)))
                .list();
        requireCovered(date, rows.getFirst());
        return new FeatureCollection<>(
                features(rows.stream().map(Row::path).filter(Objects::nonNull).toList()));
    }

    private static void requireCovered(LocalDate date, Row coverage) {
        if (coverage.first() == null || coverage.last() == null) {
            throw notFound("There is no timetable yet");
        }
        if (date.isBefore(coverage.first()) || date.isAfter(coverage.last())) {
            throw notFound("The timetable covers %s to %s".formatted(coverage.first(), coverage.last()));
        }
    }

    // Caches may keep a 404 without being told, but the next import can cover the day, so they must
    // ask again like for the lines themselves.
    private static ErrorResponseException notFound(String detail) {
        var exception = new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail), null);
        exception.getHeaders().setCacheControl(CacheControl.noCache());
        return exception;
    }

    private static RoutePath routePath(ResultSet row) throws SQLException {
        var longitudes = row.getArray("longitudes");
        var latitudes = row.getArray("latitudes");
        return new RoutePath(
                row.getString("id"),
                new LineProperties(
                        row.getString("name"),
                        row.getString("long_name"),
                        row.getString("type"),
                        row.getString("color"),
                        row.getString("text_color")),
                longitudes == null || latitudes == null ? List.of() : positions(longitudes, latitudes));
    }

    // The rows come ordered by route, so each route's paths are next to each other.
    private static List<Feature<LineProperties>> features(List<RoutePath> paths) {
        var features = new ArrayList<Feature<LineProperties>>();
        var index = 0;
        while (index < paths.size()) {
            var first = paths.get(index);
            var lines = new ArrayList<List<Position>>();
            while (index < paths.size() && paths.get(index).routeId().equals(first.routeId())) {
                var positions = paths.get(index).positions();
                if (!positions.isEmpty()) {
                    lines.add(positions);
                }
                index++;
            }
            features.add(new Feature<>(first.routeId(), new MultiLineString(lines), first.properties()));
        }
        return features;
    }

    private static List<Position> positions(Array longitudes, Array latitudes) throws SQLException {
        var x = (Double[]) longitudes.getArray();
        var y = (Double[]) latitudes.getArray();
        var positions = new ArrayList<Position>(x.length);
        for (int i = 0; i < x.length; i++) {
            positions.add(new Position(x[i], y[i]));
        }
        return positions;
    }

    private record RoutePath(String routeId, LineProperties properties, List<Position> positions) {}

    // The first and last day the timetable covers, null when it is empty, and one path of a line.
    private record Row(
            @Nullable LocalDate first,
            @Nullable LocalDate last,
            @Nullable RoutePath path) {}
}
