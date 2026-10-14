package com.vladdancea.viennapulse.importer.ingestor.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.importer.ingestor.FeedFormatException;
import com.vladdancea.viennapulse.importer.ingestor.TestFeed;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RouteReaderTest {
    private final RouteReader reader = new RouteReader();

    @TempDir
    Path dir;

    @Test
    void readsRoutesInFileOrder() throws Exception {
        var routes = """
                \uFEFF"route_id","agency_id","route_short_name","route_long_name","route_type","route_color","route_text_color"
                "U1","1","U1","Leopoldau - Oberlaa","1","E20000","FFFFFF"
                "D","1","D","Nussdorf, Beethovengang - Hauptbahnhof","0","",""
                """;
        try (var feed = TestFeed.zip(dir, "routes.txt", routes)) {
            assertThat(reader.read(feed).values())
                    .containsExactly(
                            new Route("U1", "1", "U1", "Leopoldau - Oberlaa", RouteType.SUBWAY, "E20000", "FFFFFF"),
                            new Route(
                                    "D",
                                    "1",
                                    "D",
                                    "Nussdorf, Beethovengang - Hauptbahnhof",
                                    RouteType.TRAM,
                                    null,
                                    null));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    "1","U1","","700",""  | unknown route type 700
                    "1","U1","","1","red" | color red is not six hex digits
                    "1","","","1",""      | route 1 has neither a short nor a long name
                    """)
    void rejectsRowsThatBreakTheGtfsRules(String row, String problem) throws Exception {
        var routes = "route_id,route_short_name,route_long_name,route_type,route_color\n" + row + "\n";
        try (var feed = TestFeed.zip(dir, "routes.txt", routes)) {
            assertThatThrownBy(() -> reader.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("routes.txt line 2: " + problem);
        }
    }
}
