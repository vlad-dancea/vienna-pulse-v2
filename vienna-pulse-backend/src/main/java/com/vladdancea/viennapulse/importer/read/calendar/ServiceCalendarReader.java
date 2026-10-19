package com.vladdancea.viennapulse.importer.read.calendar;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.date;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.FeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.springframework.stereotype.Component;

/// Reads `calendar.txt` into [ServiceCalendar] records.
@Component
public class ServiceCalendarReader implements FeedFileReader<ServiceCalendar> {
    @Override
    public String file() {
        return "calendar.txt";
    }

    @Override
    public ServiceCalendar parse(NamedCsvRecord row) {
        return new ServiceCalendar(
                required(row, "service_id"),
                runs(row, "monday"),
                runs(row, "tuesday"),
                runs(row, "wednesday"),
                runs(row, "thursday"),
                runs(row, "friday"),
                runs(row, "saturday"),
                runs(row, "sunday"),
                date(row, "start_date"),
                date(row, "end_date"));
    }

    @Override
    public String id(ServiceCalendar calendar) {
        return calendar.serviceId();
    }

    private static boolean runs(NamedCsvRecord row, String day) {
        var value = required(row, day);
        return switch (value) {
            case "1" -> true;
            case "0" -> false;
            default -> throw new IllegalArgumentException(day + " " + value + " is not 0 or 1");
        };
    }
}
