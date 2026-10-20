package com.vladdancea.viennapulse.importer.read.stopcall;

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

class StopCallReaderTest {
    private final StopCallReader reader = new StopCallReader();

    @TempDir
    Path dir;

    @Test
    void readsPassengerEligibilityWithoutDiscardingExcludedCalls() throws Exception {
        var csv = """
                ﻿"trip_id","stop_id","pickup_type","drop_off_type"
                " t1 ","s1","",""
                "t2","s2","1","1"
                "t3","s3","1","0"
                "t4","s4","2","3"
                """;
        var calls = new ArrayList<StopCall>();
        try (var feed = TestFeed.zip(dir, "stop_times.txt", csv)) {
            reader.read(feed, calls::add);
        }
        assertThat(calls)
                .containsExactly(
                        new StopCall("t1", "s1", true),
                        new StopCall("t2", "s2", false),
                        new StopCall("t3", "s3", true),
                        new StopCall("t4", "s4", true));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ,s1,0,0   | missing trip_id
            t1,,0,0   | missing stop_id
            t1,s1,4,0 | pickup_type 4 is not 0 to 3
            t1,s1,1,x | drop_off_type x is not 0 to 3
            """)
    void rejectsBadReferencesAndPickupRules(String row, String problem) throws Exception {
        try (var feed =
                TestFeed.zip(dir, "stop_times.txt", "trip_id,stop_id,pickup_type,drop_off_type\n" + row + "\n")) {
            assertThatThrownBy(() -> reader.read(feed, call -> {}))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("stop_times.txt line 2: " + problem);
        }
    }
}
