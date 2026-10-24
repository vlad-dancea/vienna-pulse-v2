package com.vladdancea.viennapulse.live;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// The `pulse.live.*` settings: `monitor-url` is Wiener Linien's `monitor` endpoint, `platforms-url`
/// their list of platforms, `round-interval` the pause after each round, `open-for` how long a
/// departures request keeps its station open, `max-platforms` how many platforms one request asks
/// about, `timeout` how long a request may take, and `blocked-pause` how long the backend stops
/// asking after Wiener Linien blocked it.
@ConfigurationProperties("pulse.live")
public record LiveProperties(
        URI monitorUrl,
        URI platformsUrl,
        Duration roundInterval,
        Duration openFor,
        int maxPlatforms,
        Duration timeout,
        Duration blockedPause) {}
