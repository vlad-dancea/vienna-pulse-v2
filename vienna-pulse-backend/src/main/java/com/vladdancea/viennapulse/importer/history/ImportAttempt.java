package com.vladdancea.viennapulse.importer.history;

import java.net.URI;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/// An import while it runs: when it started, where it downloads the feed from and, once the
/// download has finished, the feed's size and hash.
public record ImportAttempt(
        Instant startedAt, URI feedUrl, @Nullable FeedFile feed) {
    public ImportAttempt(Instant startedAt, URI feedUrl) {
        this(startedAt, feedUrl, null);
    }

    public ImportAttempt withFeed(FeedFile feed) {
        return new ImportAttempt(startedAt, feedUrl, feed);
    }
}
