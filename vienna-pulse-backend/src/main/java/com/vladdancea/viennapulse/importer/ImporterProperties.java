package com.vladdancea.viennapulse.importer;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// The `pulse.importer.*` settings: `feed-url` is the GTFS ZIP to download.
@ConfigurationProperties("pulse.importer")
public record ImporterProperties(URI feedUrl) {}
