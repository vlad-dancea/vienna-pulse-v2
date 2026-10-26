package com.vladdancea.viennapulse.live;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/// A departure in a `monitor` answer: the number of its platform, its line's name, its direction
/// (`H` or `R`), the destination their displays show, if any, when Wiener Linien plan it, when they
/// expect it, if they know, and the minutes their displays count down to it.
public record LiveDeparture(
        int platform,
        String line,
        String direction,
        @Nullable String towards,
        Instant planned,
        @Nullable Instant real,
        int countdown) {}
