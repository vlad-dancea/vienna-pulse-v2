package com.vladdancea.viennapulse.api;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/// Tells the timetables apart, so that a kept response notices when an import replaced its own.
@Component
@RequiredArgsConstructor
class TimetableVersion {
    private final JdbcClient jdbc;

    /// The internal id of the `timetable` schema. Every import loads a new schema and renames it to
    /// `timetable`, so the id changes with each one, without the API sharing code with the importer.
    long current() {
        return jdbc.sql("SELECT CAST(CAST('timetable' AS regnamespace) AS oid)")
                .query(Long.class)
                .single();
    }
}
