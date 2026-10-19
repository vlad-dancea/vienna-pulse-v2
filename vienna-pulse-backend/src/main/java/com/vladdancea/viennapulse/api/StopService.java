package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.Feature;
import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import com.vladdancea.viennapulse.api.geojson.Point;
import lombok.RequiredArgsConstructor;
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
                        SELECT array_to_string((string_to_array(id, ':'))[1:3], ':') AS id,
                               mode() WITHIN GROUP (ORDER BY name) AS name,
                               avg(latitude) AS latitude,
                               avg(longitude) AS longitude
                        FROM timetable.stops
                        GROUP BY 1
                        ORDER BY 1
                        """)
                .query((row, number) -> new Feature<>(
                        row.getString("id"),
                        new Point(row.getDouble("longitude"), row.getDouble("latitude")),
                        new StopProperties(row.getString("name"))))
                .list();
        return new FeatureCollection<>(features);
    }
}
