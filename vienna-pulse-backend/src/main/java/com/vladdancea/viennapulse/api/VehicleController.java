package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Serves the vehicles to draw on the map.
@RestController
@RequiredArgsConstructor
public class VehicleController {
    // Every map asks every 15 seconds and glides its vehicles over that time, so positions up to one
    // step old do not show, and the database works out the vehicles at most once per step.
    private static final Duration STEP = Duration.ofSeconds(5);

    private final VehicleService vehicleService;
    private final Clock clock;
    private final ResponseCache cache;

    /// Where the timetable puts every running vehicle at the start of the current five-second step,
    /// as GeoJSON points that are also served as plain JSON for MapLibre. Every request in a step
    /// gets the same positions, and the ETag answers with an empty 304 until the next step.
    @GetMapping(
            path = "/api/vehicles",
            produces = {FeatureCollection.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<byte[]> vehicles() {
        return cache.get("vehicles", () -> stepStart(clock.instant()), vehicleService::vehicles);
    }

    private static Instant stepStart(Instant now) {
        var second = now.getEpochSecond();
        return Instant.ofEpochSecond(second - Math.floorMod(second, STEP.toSeconds()));
    }
}
