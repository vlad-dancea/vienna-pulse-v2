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

    /// Every stop, ordered by id. A fixed order keeps the response, and with it its ETag, the same
    /// for as long as the stops are.
    public FeatureCollection<StopProperties> stops() {
        var features = jdbc.sql("SELECT id, name, latitude, longitude FROM timetable.stops ORDER BY id")
                .query((row, number) -> new Feature<>(
                        row.getString("id"),
                        new Point(row.getDouble("longitude"), row.getDouble("latitude")),
                        new StopProperties(row.getString("name"))))
                .list();
        return new FeatureCollection<>(features);
    }
}
