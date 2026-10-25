package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import java.time.Clock;
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
    private final Clock clock;
    private final CachedResponses responses;

    /// Where the timetable puts every running vehicle at the start of the current five-second step,
    /// as GeoJSON points that are also served as plain JSON for MapLibre, with the path each one
    /// follows from there. Every request in a step gets the same answer, and the ETag answers with an
    /// empty 304 until the next step.
    @GetMapping(
            path = "/api/vehicles",
            produces = {FeatureCollection.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<byte[]> vehicles() {
        return responses.vehicles(stepStart(clock.instant())).response();
    }

    private static Instant stepStart(Instant now) {
        var second = now.getEpochSecond();
        return Instant.ofEpochSecond(second - Math.floorMod(second, ApiConfiguration.VEHICLE_STEP.toSeconds()));
    }
}
