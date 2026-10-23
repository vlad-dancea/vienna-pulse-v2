package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.Feature;
import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import com.vladdancea.viennapulse.api.geojson.Point;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/// Reads the stops of the current timetable.
@Service
@RequiredArgsConstructor
public class StopService {
    private final JdbcClient jdbc;

    /// Every stop, ordered by id, with its platforms merged into one point in their middle. A fixed
    /// order keeps the response, and with it its ETag, the same for as long as the stops are.
    ///
    /// The feed lists each platform as a stop of its own and has no `parent_station` to group them.
    /// Its ids are IFOPT-style global stop ids instead, like `at:49:1320:0:3`, where the first three
    /// parts name the stop and the rest its platform. A stop takes the name most of its platforms
    /// use; on a tie, the first in alphabetical order, so that the response stays the same.
    public FeatureCollection<StopProperties> stops() {
        var features = jdbc.sql("""
                        WITH grouped AS (
                            SELECT merged_id AS id,
                               mode() WITHIN GROUP (ORDER BY name) AS name,
                               avg(latitude) AS latitude,
                               avg(longitude) AS longitude
                            FROM timetable.stops
                            GROUP BY merged_id
                        ), modes AS (
                            SELECT s.merged_id AS id,
                                   array_agg(DISTINCT lower(type) ORDER BY lower(type)) AS modes
                            FROM timetable.stop_modes m
                            JOIN timetable.stops s ON s.id = m.stop_id
                            CROSS JOIN LATERAL unnest(m.types) AS type
                            GROUP BY s.merged_id
                        )
                        SELECT g.*, CASE WHEN EXISTS (SELECT FROM timetable.stop_modes)
                                         THEN coalesce(m.modes, ARRAY[]::text[]) END AS modes
                        FROM grouped g LEFT JOIN modes m USING (id)
                        ORDER BY g.id
                        """)
                .query((row, number) -> new Feature<>(
                        row.getString("id"),
                        new Point(row.getDouble("longitude"), row.getDouble("latitude")),
                        new StopProperties(row.getString("name"), modes(row))))
                .list();
        return new FeatureCollection<>(features);
    }

    // Null distinguishes an upgrade awaiting its first classification from a known unused stop.
    private static @Nullable List<String> modes(ResultSet row) throws SQLException {
        var modes = row.getArray("modes");
        return modes == null ? null : Arrays.asList((String[]) modes.getArray());
    }
}
