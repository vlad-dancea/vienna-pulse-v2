package com.vladdancea.viennapulse.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/// What the map knows about a line besides its paths: its public name, its kind (`tram`, `subway`,
/// `bus` and so on) and the feed's colours as `#RRGGBB`, if it has any.
public record LineProperties(
        String name,
        @Nullable String longName,
        String type,
        @Nullable String color,
        @Nullable String textColor) {

    /// Reads the line from the `short_name`, `long_name`, `type`, `color` and `text_color` columns
    /// of its `timetable.routes` row, which every query selects under those names.
    static LineProperties from(ResultSet row) throws SQLException {
        var shortName = row.getString("short_name");
        var longName = row.getString("long_name");
        return new LineProperties(
                shortName != null ? shortName : longName,
                longName,
                row.getString("type").toLowerCase(Locale.ROOT),
                hex(row.getString("color")),
                hex(row.getString("text_color")));
    }

    private static @Nullable String hex(@Nullable String color) {
        return color == null ? null : "#" + color;
    }
}
