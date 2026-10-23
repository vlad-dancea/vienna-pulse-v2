package com.vladdancea.viennapulse.importer;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/// The `pulse.importer.*` settings: `feed-url` is the GTFS ZIP to download, which may be at most
/// `max-download-size`, and `import-when-empty` imports it right after startup while the timetable
/// is still empty.
@ConfigurationProperties("pulse.importer")
public record ImporterProperties(URI feedUrl, DataSize maxDownloadSize, boolean importWhenEmpty) {}
