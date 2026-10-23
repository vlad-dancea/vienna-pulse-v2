package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Serves the stops to draw on the map.
@RestController
@RequiredArgsConstructor
public class StopController {
    private final StopService stopService;
    private final TimetableVersion timetable;
    private final ResponseCache cache;

    /// Every stop as a GeoJSON point, also served as plain JSON because MapLibre asks for that.
    /// The stops change only with an import, so they are read once per timetable and the ETag
    /// answers with an empty 304 until the next one.
    @GetMapping(
            path = "/api/stops",
            produces = {FeatureCollection.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<byte[]> stops() {
        return cache.get("stops", timetable::current, version -> stopService.stops());
    }
}
