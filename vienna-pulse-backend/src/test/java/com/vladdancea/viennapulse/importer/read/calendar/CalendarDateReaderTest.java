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

class CalendarDateReaderTest {
    private final CalendarDateReader reader = new CalendarDateReader();

    @TempDir
    Path dir;

    @Test
    void readsDatesInFileOrder() throws Exception {
        var dates = """
                ﻿service_id,date,exception_type
                "T0","20260406","2"
                "T0#100","20260406","1"
                """;
        try (var feed = TestFeed.zip(dir, "calendar_dates.txt", dates)) {
            assertThat(reader.read(feed).values())
                    .containsExactly(
                            new CalendarDate("T0", LocalDate.of(2026, 4, 6), ExceptionType.REMOVED),
                            new CalendarDate("T0#100", LocalDate.of(2026, 4, 6), ExceptionType.ADDED));
        }
    }

    @Test
    void rejectsADateTheServiceAlreadyNamed() throws Exception {
        var dates = "service_id,date,exception_type\nT0,20260406,2\nT0,20260406,1\n";
        try (var feed = TestFeed.zip(dir, "calendar_dates.txt", dates)) {
            assertThatThrownBy(() -> reader.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("calendar_dates.txt line 3: duplicate id T0 on 2026-04-06");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    ,20260406,2   | missing service_id
                    T0,,2         | missing date
                    T0,2026046,2  | date 2026046 is not a date in YYYYMMDD form
                    T0,20260406,  | missing exception_type
                    T0,20260406,0 | unknown exception type 0
                    """)
    void rejectsRowsThatBreakTheGtfsRules(String row, String problem) throws Exception {
        var dates = "service_id,date,exception_type\n" + row + "\n";
        try (var feed = TestFeed.zip(dir, "calendar_dates.txt", dates)) {
            assertThatThrownBy(() -> reader.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("calendar_dates.txt line 2: " + problem);
        }
    }
}
