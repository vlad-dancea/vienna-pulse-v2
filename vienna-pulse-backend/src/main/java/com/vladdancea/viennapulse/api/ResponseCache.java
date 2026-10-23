package com.vladdancea.viennapulse.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import tools.jackson.databind.json.JsonMapper;

/// Keeps the latest response of each endpoint, serialized and with its ETag, so that every request
/// for the same key shares one query and a client that has the body gets a 304 without one.
@Component
@RequiredArgsConstructor
class ResponseCache {
    private final JsonMapper json;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();

    /// The response of `endpoint` for the current `key`, computed with `body` only when the one kept
    /// has another key. Requests that miss together wait for one of them to compute it, and each reads
    /// the key again once its turn comes, so a request that waited into the next step does not bring
    /// back the previous one.
    ///
    /// Browsers must check before reusing the response, which the ETag answers until the key changes.
    <K> ResponseEntity<byte[]> get(String endpoint, Supplier<K> key, Function<K, ?> body) {
        var slot = slots.computeIfAbsent(endpoint, name -> new Slot());
        var kept = slot.latest;
        if (kept == null || !kept.key().equals(key.get())) {
            kept = slot.refresh(key, body);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .eTag(kept.eTag())
                .body(kept.body());
    }

    /// Forgets every response, for tests that change the timetable without an import.
    void clear() {
        slots.clear();
    }

    private final class Slot {
        private volatile @Nullable Entry latest;

        synchronized <K> Entry refresh(Supplier<K> key, Function<K, ?> body) {
            var wanted = key.get();
            var kept = latest;
            if (kept != null && kept.key().equals(wanted)) {
                return kept;
            }
            var serialized = json.writeValueAsBytes(body.apply(wanted));
            // Weak, like the ETag filter's, because Tomcat only compresses responses with a weak ETag.
            var entry = new Entry(wanted, serialized, "W/\"" + DigestUtils.md5DigestAsHex(serialized) + "\"");
            latest = entry;
            return entry;
        }
    }

    // Nothing compares entries, so the body's equals, which compares references, never matters.
    @SuppressWarnings("ArrayRecordComponent")
    private record Entry(Object key, byte[] body, String eTag) {}
}
