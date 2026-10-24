package com.vladdancea.viennapulse.importer;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/// The `pulse.importer.*` settings: `feed-url` is the GTFS ZIP to download, which may be at most
/// `max-download-size`, `import-when-empty` imports it right after startup while a timetable table
/// is still empty, and `retry-delays` are the pauses before each retry of a failed import.
@ConfigurationProperties("pulse.importer")
public record ImporterProperties(
        URI feedUrl, DataSize maxDownloadSize, boolean importWhenEmpty, List<Duration> retryDelays) {}
