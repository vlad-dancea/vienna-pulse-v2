package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.api.geojson.FeatureCollection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/// Serves the lines to draw on the map.
@RestController
@RequiredArgsConstructor
public class LineController {
    // The day of the timetable, not of the server's time zone.
    private static final ZoneId VIENNA = ZoneId.of("Europe/Vienna");
    // Spring's ISO date format also takes an offset, like 2026-10-20Z, and years past 9999 that
    // PostgreSQL cannot store, so the date is checked by hand.
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final LineService lineService;
    private final Clock clock;
    private final TimetableVersion timetable;
    private final ResponseCache cache;

    /// The lines running on `date`, given as `YYYY-MM-DD` and today in Vienna if left out, as GeoJSON
    /// that is also served as plain JSON for MapLibre. Any other date, empty included, gets a 400,
    /// and a date the timetable does not cover a 404.
    /// Today's lines, which every map asks for, are read once per timetable and the ETag answers
    /// with an empty 304 until the next import. Other days are read on every request, so that asking
    /// for many of them cannot fill the memory. Like the stops, browsers must check before reusing
    /// the response, since the next import can change the lines of any day.
    @GetMapping(
            path = "/api/lines",
            produces = {FeatureCollection.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> lines(@RequestParam(required = false) @Nullable String date) {
        var today = LocalDate.now(clock.withZone(VIENNA));
        var day = date != null ? parse(date) : today;
        if (!day.equals(today)) {
            return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(lineService.lines(day));
        }
        return cache.get("lines", () -> new Timetable(timetable.current(), day), key -> lineService.lines(key.day()));
    }

    private static LocalDate parse(String date) {
        if (DATE.matcher(date).matches()) {
            try {
                return LocalDate.parse(date);
            } catch (DateTimeParseException impossible) {
                // A day that does not exist, like 2026-02-30, gets the same answer as any other text.
            }
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "The date must be a day written as YYYY-MM-DD, not '%s'".formatted(date));
    }

    private record Timetable(long version, LocalDate day) {}
}
