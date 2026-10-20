package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Serves the vehicles to draw on the map.
@RestController
@RequiredArgsConstructor
public class VehicleController {
    private final VehicleService vehicleService;
    private final Clock clock;

    /// Where the timetable puts every running vehicle now, as GeoJSON points that are also served
    /// as plain JSON for MapLibre. Browsers must check before reusing the response, which goes stale
    /// as soon as the vehicles move on.
    @GetMapping(
            path = "/api/vehicles",
            produces = {FeatureCollection.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<FeatureCollection<VehicleProperties>> vehicles() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(vehicleService.vehicles(clock.instant()));
    }
}
