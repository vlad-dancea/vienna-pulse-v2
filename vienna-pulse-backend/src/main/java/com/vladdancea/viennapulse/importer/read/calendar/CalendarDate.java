package com.vladdancea.viennapulse.importer.read.calendar;

import java.time.LocalDate;

/// One row of `calendar_dates.txt`: a single day added to or removed from a service, which wins
/// over its weekly [ServiceCalendar]. The constructor rejects a row that breaks the GTFS rules with
/// an [IllegalArgumentException].
public record CalendarDate(String serviceId, LocalDate date, ExceptionType exceptionType) {
    public CalendarDate {
        if (serviceId.isBlank()) {
            throw new IllegalArgumentException("service id is blank");
        }
    }
}
