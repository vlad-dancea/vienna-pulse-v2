package com.vladdancea.viennapulse.api;

import java.time.ZoneId;

/// What every reader of the timetable has to agree on.
final class Timetable {
    /// The time zone the timetable's days and times are written in.
    static final ZoneId ZONE = ZoneId.of("Europe/Vienna");

    private Timetable() {}
}
