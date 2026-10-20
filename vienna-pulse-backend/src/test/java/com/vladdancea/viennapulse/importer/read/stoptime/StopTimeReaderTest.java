package com.vladdancea.viennapulse.importer.read.stoptime;

import static com.vladdancea.viennapulse.importer.read.stoptime.PickupDropOffType.COORDINATE_WITH_DRIVER;
import static com.vladdancea.viennapulse.importer.read.stoptime.PickupDropOffType.NONE;
import static com.vladdancea.viennapulse.importer.read.stoptime.PickupDropOffType.PHONE_AGENCY;
import static com.vladdancea.viennapulse.importer.read.stoptime.PickupDropOffType.REGULAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.TestFeed;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StopTimeReaderTest {
    private final StopTimeReader reader = new StopTimeReader();

    @TempDir
    Path dir;

    @Test
    void readsStopTimesInFileOrder() throws Exception {
        var csv = """
                ﻿"trip_id","arrival_time","departure_time","stop_id","stop_sequence","pickup_type","drop_off_type","shape_dist_traveled"
                "1.T0.11-SEV-1-j26-1.2.R","24:38:00","24:38:00","at:43:5201:0:2","1","0","0","0.00"
                " t2 ","7:05:09","07:06:00","s2","2","1","3","1847.57"
                "t3","","","s3","0","2","",""
                """;
        var read = new ArrayList<StopTime>();
        try (var feed = TestFeed.zip(dir, "stop_times.txt", csv)) {
            reader.read(feed, read::add);
        }
        assertThat(read)
                .containsExactly(
                        new StopTime(
                                "1.T0.11-SEV-1-j26-1.2.R", 1, "at:43:5201:0:2", 88680, 88680, REGULAR, REGULAR, 0.0),
                        new StopTime("t2", 2, "s2", 25509, 25560, NONE, COORDINATE_WITH_DRIVER, 1847.57),
                        new StopTime("t3", 0, "s3", null, null, PHONE_AGENCY, REGULAR, null));
    }

    @Test
    void readsWithoutTheOptionalColumns() throws Exception {
        var csv = "trip_id,stop_id,stop_sequence\nt1,s1,3\n";
        var read = new ArrayList<StopTime>();
        try (var feed = TestFeed.zip(dir, "stop_times.txt", csv)) {
            reader.read(feed, read::add);
        }
        assertThat(read).containsExactly(new StopTime("t1", 3, "s1", null, null, REGULAR, REGULAR, null));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ,s1,1,08:00:00,08:00:00,0,0,0  | missing trip_id
            t1,,1,08:00:00,08:00:00,0,0,0  | missing stop_id
            t1,s1,,08:00:00,08:00:00,0,0,0 | missing stop_sequence
            t1,s1,x,08:00:00,08:00:00,0,0,0 | stop_sequence x is not an integer
            t1,s1,-1,08:00:00,08:00:00,0,0,0 | stop_sequence -1 is negative
            t1,s1,1,8:00,08:00:00,0,0,0    | arrival_time 8:00 is not a time in HH:MM:SS form
            t1,s1,1,08:00:00,08:60:00,0,0,0 | departure_time 08:60:00 is not a time in HH:MM:SS form
            t1,s1,1,08:01:00,08:00:00,0,0,0 | arrival_time is after departure_time
            t1,s1,1,08:00:00,08:00:00,4,0,0 | pickup_type 4 is not 0 to 3
            t1,s1,1,08:00:00,08:00:00,0,x,0 | drop_off_type x is not 0 to 3
            t1,s1,1,08:00:00,08:00:00,0,0,x | shape_dist_traveled x is not a number
            t1,s1,1,08:00:00,08:00:00,0,0,-1 | shape_dist_traveled -1.0 is not a finite distance
            """)
    void rejectsRowsThatBreakTheGtfsRules(String row, String problem) throws Exception {
        var csv = "trip_id,stop_id,stop_sequence,arrival_time,departure_time,pickup_type,drop_off_type,"
                + "shape_dist_traveled\n" + row + "\n";
        try (var feed = TestFeed.zip(dir, "stop_times.txt", csv)) {
            assertThatThrownBy(() -> reader.read(feed, stopTime -> {}))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("stop_times.txt line 2: " + problem);
        }
    }
}
