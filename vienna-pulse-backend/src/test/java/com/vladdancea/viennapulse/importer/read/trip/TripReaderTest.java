package com.vladdancea.viennapulse.importer.read.trip;

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

class TripReaderTest {
    private final TripReader reader = new TripReader();

    @TempDir
    Path dir;

    @Test
    void readsTripsInFileOrder() throws Exception {
        var trips = """
                ﻿"route_id","service_id","trip_id","shape_id","trip_headsign","direction_id","block_id"
                "11-SEV-1-j26-1","T0","1.T0.11-SEV-1-j26-1.2.R","11-SEV-1-j26-1.2.R","Wien Siebenhirten","1",""
                "11-SEV-1-j26-1","T2","10.T2.11-SEV-1-j26-1.1.H","11-SEV-1-j26-1.1.H","Wiener Neudorf","0","7"
                "D-j26-1"," T0 ","3.T0.D-j26-1.1.H","","","",""
                """;
        var read = new ArrayList<Trip>();
        try (var feed = TestFeed.zip(dir, "trips.txt", trips)) {
            reader.read(feed, read::add);

            assertThat(read)
                    .containsExactly(
                            new Trip(
                                    "1.T0.11-SEV-1-j26-1.2.R",
                                    "11-SEV-1-j26-1",
                                    "T0",
                                    "11-SEV-1-j26-1.2.R",
                                    "Wien Siebenhirten",
                                    1),
                            new Trip(
                                    "10.T2.11-SEV-1-j26-1.1.H",
                                    "11-SEV-1-j26-1",
                                    "T2",
                                    "11-SEV-1-j26-1.1.H",
                                    "Wiener Neudorf",
                                    0),
                            new Trip("3.T0.D-j26-1.1.H", "D-j26-1", "T0", null, null, null));
        }
    }

    @Test
    void readsWithoutTheOptionalColumns() throws Exception {
        var trips = "route_id,service_id,trip_id\nD,T0,t1\n";
        var read = new ArrayList<Trip>();
        try (var feed = TestFeed.zip(dir, "trips.txt", trips)) {
            reader.read(feed, read::add);
            assertThat(read).containsExactly(new Trip("t1", "D", "T0", null, null, null));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    ,T0,t1,s1,0   | missing route_id
                    D,,t1,s1,0    | missing service_id
                    D,T0,,s1,0    | missing trip_id
                    D,T0,t1,s1,2  | direction_id 2 is not 0 or 1
                    D,T0,t1,s1,-1 | direction_id -1 is not 0 or 1
                    D,T0,t1,s1,up | direction_id up is not an integer
                    """)
    void rejectsRowsThatBreakTheGtfsRules(String row, String problem) throws Exception {
        var trips = "route_id,service_id,trip_id,shape_id,direction_id\n" + row + "\n";
        try (var feed = TestFeed.zip(dir, "trips.txt", trips)) {
            assertThatThrownBy(() -> reader.read(feed, trip -> {}))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("trips.txt line 2: " + problem);
        }
    }
}
