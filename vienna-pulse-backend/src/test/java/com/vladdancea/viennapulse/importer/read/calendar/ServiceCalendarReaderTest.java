package com.vladdancea.viennapulse.importer.read.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.TestFeed;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServiceCalendarReaderTest {
    private final ServiceCalendarReader reader = new ServiceCalendarReader();

    @TempDir
    Path dir;

    @Test
    void readsCalendarsInFileOrder() throws Exception {
        var calendars = """
                ﻿service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                "T0#1","1","1","1","1","0","0","0","20260427","20260503"
                "T2","0","0","0","0","0","1","1","20261212","20261212"
                """;
        try (var feed = TestFeed.zip(dir, "calendar.txt", calendars)) {
            assertThat(reader.read(feed).values())
                    .containsExactly(
                            new ServiceCalendar(
                                    "T0#1",
                                    true,
                                    true,
                                    true,
                                    true,
                                    false,
                                    false,
                                    false,
                                    LocalDate.of(2026, 4, 27),
                                    LocalDate.of(2026, 5, 3)),
                            new ServiceCalendar(
                                    "T2",
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    true,
                                    true,
                                    LocalDate.of(2026, 12, 12),
                                    LocalDate.of(2026, 12, 12)));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    ,1,1,1,1,1,0,0,20260101,20260131     | missing service_id
                    T0,1,1,2,1,1,0,0,20260101,20260131   | wednesday 2 is not 0 or 1
                    T0,1,1,1,1,1,0,,20260101,20260131    | missing sunday
                    T0,1,1,1,1,1,0,0,2026-01-01,20260131 | start_date 2026-01-01 is not a date in YYYYMMDD form
                    T0,1,1,1,1,1,0,0,20260101,20260231   | end_date 20260231 is not a date in YYYYMMDD form
                    T0,1,1,1,1,1,0,0,20260201,20260131   | service T0 starts on 2026-02-01, after it ends on 2026-01-31
                    """)
    void rejectsRowsThatBreakTheGtfsRules(String row, String problem) throws Exception {
        var calendars = "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                + row + "\n";
        try (var feed = TestFeed.zip(dir, "calendar.txt", calendars)) {
            assertThatThrownBy(() -> reader.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("calendar.txt line 2: " + problem);
        }
    }
}
