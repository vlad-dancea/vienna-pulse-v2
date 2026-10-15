package com.vladdancea.viennapulse.importer.read.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.TestFeed;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StopReaderTest {
    private final StopReader reader = new StopReader();

    @TempDir
    Path dir;

    @Test
    void readsStops() throws Exception {
        var stops = """
                \uFEFF"stop_id","stop_name","stop_lat","stop_lon","zone_id"
                "at:49:1","Karlsplatz","48.20024","16.36951","100"
                "at:49:2","Stephansplatz","48.20845","16.37212",""
                """;
        try (var feed = TestFeed.zip(dir, "stops.txt", stops)) {
            assertThat(reader.read(feed).values())
                    .containsExactly(
                            new Stop("at:49:1", "Karlsplatz", 48.20024, 16.36951, "100"),
                            new Stop("at:49:2", "Stephansplatz", 48.20845, 16.37212, null));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    "s1","Karlsplatz","48.2","16.37","1"   | unsupported location type 1
                    "s1","Karlsplatz","north","16.37",""   | stop_lat north is not a number
                    "s1","Karlsplatz","91","16.37",""      | stop_lat 91.0 is not between -90 and 90
                    "s1","","48.2","16.37",""              | missing stop_name
                    """)
    void rejectsRowsThatBreakTheGtfsRules(String row, String problem) throws Exception {
        var stops = "stop_id,stop_name,stop_lat,stop_lon,location_type\n" + row + "\n";
        try (var feed = TestFeed.zip(dir, "stops.txt", stops)) {
            assertThatThrownBy(() -> reader.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("stops.txt line 2: " + problem);
        }
    }
}
