package com.vladdancea.viennapulse.live;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/// A departure in a `monitor` answer: the number of its platform, its line's name, its direction
/// (`H` or `R`), when Wiener Linien plan it and when they expect it, if they know.
public record LiveDeparture(
        int platform,
        String line,
        String direction,
        Instant planned,
        @Nullable Instant real) {}
