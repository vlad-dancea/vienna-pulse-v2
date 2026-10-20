package com.vladdancea.viennapulse.api;

import org.jspecify.annotations.Nullable;

/// What the map knows about a line besides its paths: its public name, its kind (`tram`, `subway`,
/// `bus` and so on) and the feed's colours as `#RRGGBB`, if it has any.
public record LineProperties(
        String name,
        @Nullable String longName,
        String type,
        @Nullable String color,
        @Nullable String textColor) {}
