package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Serves the stops to draw on the map.
@RestController
@RequiredArgsConstructor
public class StopController {
    private final StopService stopService;

    /// Every stop as a GeoJSON point, also served as plain JSON because MapLibre asks for that.
    /// Browsers must check before reusing the response, which the ETag answers with an empty 304
    /// until the next import changes the stops.
    @GetMapping(
            path = "/api/stops",
            produces = {FeatureCollection.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<FeatureCollection<StopProperties>> stops() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(stopService.stops());
    }
}
