package com.vladdancea.viennapulse.importer.read.calendar;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.date;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.FeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.springframework.stereotype.Component;

/// Reads `calendar_dates.txt` into [CalendarDate] records. A service may name each date only once,
/// so the pair of service and date is the id.
@Component
public class CalendarDateReader implements FeedFileReader<CalendarDate> {
    @Override
    public String file() {
        return "calendar_dates.txt";
    }

    @Override
    public CalendarDate parse(NamedCsvRecord row) {
        return new CalendarDate(
                required(row, "service_id"),
                date(row, "date"),
                ExceptionType.fromCode(required(row, "exception_type")));
    }

    @Override
    public String id(CalendarDate date) {
        return date.serviceId() + " on " + date.date();
    }
}
