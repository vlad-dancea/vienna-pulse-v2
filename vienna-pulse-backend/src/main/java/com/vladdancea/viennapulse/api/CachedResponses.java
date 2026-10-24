package com.vladdancea.viennapulse.api;

import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.json.JsonMapper;

/// The responses that change only with the timetable or the time step, serialized once with their
/// ETag and kept in the caches [ApiConfiguration] sets up. Keeping the bytes, not the features,
/// spares every request Jackson and the hash of up to 3 MB, so a 304 costs next to nothing.
///
/// Each cache computes a missing response once while the other requests for it wait.
@Component
@RequiredArgsConstructor
class CachedResponses {
    static final String STOPS = "stops";
    static final String LINES = "lines";
    static final String VEHICLES = "vehicles";

    private final StopService stopService;
    private final LineService lineService;
    private final VehicleService vehicleService;
    private final JsonMapper json;

    /// Every stop of the timetable with the version `timetable`, which only names the cache entry.
    @Cacheable(cacheNames = STOPS, sync = true)
    public Serialized stops(long timetable) {
        return serialize(stopService.stops());
    }

    /// The lines running on `day` in the timetable with the version `timetable`.
    @Cacheable(cacheNames = LINES, sync = true)
    public Serialized lines(long timetable, LocalDate day) {
        return serialize(lineService.lines(day));
    }

    /// Where the timetable puts every running vehicle at `stepStart`.
    @Cacheable(cacheNames = VEHICLES, sync = true)
    public Serialized vehicles(Instant stepStart) {
        return serialize(vehicleService.vehicles(stepStart));
    }

    private Serialized serialize(Object body) {
        var bytes = json.writeValueAsBytes(body);
        // Weak, like the ETag filter's, because Tomcat only compresses responses with a weak ETag.
        return new Serialized(bytes, "W/\"" + DigestUtils.md5DigestAsHex(bytes) + "\"");
    }

    /// A response body as sent and its ETag.
    // Nothing compares these, so the body's equals, which compares references, never matters.
    @SuppressWarnings("ArrayRecordComponent")
    record Serialized(byte[] body, String eTag) {
        /// A 200 with the body that browsers must check before reusing. Spring answers a request whose
        /// `If-None-Match` carries the ETag with an empty 304 instead.
        ResponseEntity<byte[]> response() {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    .eTag(eTag)
                    .body(body);
        }
    }
}
