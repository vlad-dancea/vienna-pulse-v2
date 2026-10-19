package com.vladdancea.viennapulse.importer.read.calendar;

import java.time.LocalDate;

/// One row of `calendar.txt`: the weekdays a service runs on between two dates, both included.
/// The constructor rejects a row that breaks the GTFS rules with an [IllegalArgumentException].
public record ServiceCalendar(
        String serviceId,
        boolean monday,
        boolean tuesday,
        boolean wednesday,
        boolean thursday,
        boolean friday,
        boolean saturday,
        boolean sunday,
        LocalDate startDate,
        LocalDate endDate) {

    public ServiceCalendar {
        if (serviceId.isBlank()) {
            throw new IllegalArgumentException("service id is blank");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "service " + serviceId + " starts on " + startDate + ", after it ends on " + endDate);
        }
    }
}
