package com.vladdancea.viennapulse.importer.ingestor.route;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// One row of `routes.txt`. The constructor rejects a row that breaks the GTFS rules with an
/// [IllegalArgumentException].
public record Route(
        String id,
        @Nullable String agencyId,
        @Nullable String shortName,
        @Nullable String longName,
        RouteType type,
        @Nullable String color,
        @Nullable String textColor) {

    private static final Pattern HEX_COLOR = Pattern.compile("\\p{XDigit}{6}");

    public Route {
        if (id.isBlank()) {
            throw new IllegalArgumentException("route id is blank");
        }
        if (shortName == null && longName == null) {
            throw new IllegalArgumentException("route " + id + " has neither a short nor a long name");
        }
        validateHexColor("color", color);
        validateHexColor("text color", textColor);
    }

    private static void validateHexColor(String field, @Nullable String color) {
        if (color != null && !HEX_COLOR.matcher(color).matches()) {
            throw new IllegalArgumentException(field + " " + color + " is not six hex digits");
        }
    }
}
