package com.vladdancea.viennapulse.api;

import java.time.ZoneId;

/// What every reader of the timetable has to agree on.
final class Timetable {
    /// The time zone the timetable's days and times are written in, which the database's
    /// `pulse.service_day_origin` names too.
    static final ZoneId ZONE = ZoneId.of("Europe/Vienna");

    private Timetable() {}
}
