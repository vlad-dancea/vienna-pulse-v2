package com.vladdancea.viennapulse.api;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/// Serves the departure board of a stop.
@RestController
@RequiredArgsConstructor
public class DepartureController {
    private final DepartureService departureService;
    private final Clock clock;

    /// The next departures from the stop with the merged id the map shows, like `at:49:1320`, with
    /// Wiener Linien's live times, and a 404 for any other id. The request opens the stop for live
    /// times, which the first answer has when the fair-use rules let the backend ask at once. Browsers
    /// must check before reusing the response, which goes stale as soon as the first departure leaves.
    @GetMapping("/api/stops/{id}/departures")
    public ResponseEntity<Departures> departures(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(departureService.departures(id, clock.instant()));
    }
}
