package com.vladdancea.viennapulse.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestClock;
import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.importer.download.FeedServer;
import com.vladdancea.viennapulse.importer.read.TestFeed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

// The whole import: the feed in src/test/resources/gtfs is downloaded over HTTP, validated and
// stored, and the API then answers from it.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ImportJobTest {
    // The properties of the two lines, as the API serves them.
    private static final String U1 = """
            {"name": "U1", "longName": "Oberlaa - Leopoldau", "type": "subway", "color": "#E3000F",
             "textColor": "#FFFFFF"}""";
    private static final String TRAM_2 = """
            {"name": "2", "longName": "Dornbach - Friedrich-Engels-Platz", "type": "tram", "color": "#C00808",
             "textColor": "#FFFFFF"}""";

    @AutoClose
    static final FeedServer SERVER = FeedServer.start();

    @Autowired
    ImportJob importJob;

    @Autowired
    RestTestClient client;

    @TempDir
    Path dir;

    @Autowired
    TestClock clock;

    // The downloader reads the URL from the properties, so this class gets a Spring context of its
    // own, which still shares the test run's database.
    @DynamicPropertySource
    static void feedUrl(DynamicPropertyRegistry registry) {
        registry.add("pulse.importer.feed-url", SERVER::uri);
    }

    @BeforeEach
    void setTheClock() {
        // 08:03 on Tuesday 2026-10-20 in Vienna.
        clock.set("2026-10-20T06:03:00Z");
    }

    @Test
    void servesTheImportedFeed() throws IOException {
        importFeed(TestFeed.fixture());

        assertThat(FeedServer.downloads()).isEmpty();
        // Schwedenplatz merges the platforms of both lines.
        client.get()
                .uri("/api/stops")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                {"type": "FeatureCollection", "features": [
                  {"type": "Feature", "id": "at:49:1198",
                   "geometry": {"type": "Point", "coordinates": [16.37790011, 48.21188131]},
                   "properties": {"name": "Schwedenplatz", "modes": ["subway", "tram"]}},
                  {"type": "Feature", "id": "at:49:1320",
                   "geometry": {"type": "Point", "coordinates": [16.37193979, 48.20846908]},
                   "properties": {"name": "Stephansplatz", "modes": ["subway"]}},
                  {"type": "Feature", "id": "at:49:419",
                   "geometry": {"type": "Point", "coordinates": [16.37940479, 48.21388065]},
                   "properties": {"name": "Gredlerstraße", "modes": ["tram"]}},
                  {"type": "Feature", "id": "at:49:619",
                   "geometry": {"type": "Point", "coordinates": [16.38276448, 48.21049848]},
                   "properties": {"name": "Julius-Raab-Platz", "modes": ["tram"]}},
                  {"type": "Feature", "id": "at:49:657",
                   "geometry": {"type": "Point", "coordinates": [16.36885856, 48.20125475]},
                   "properties": {"name": "Karlsplatz", "modes": ["subway"]}}
                ]}
                """, JsonCompareMode.STRICT);
        client.get()
                .uri("/api/lines")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                {"type": "FeatureCollection", "features": [
                  {"type": "Feature", "id": "21-U1-j26-1",
                   "geometry": {"type": "MultiLineString", "coordinates": [
                     [[16.378058, 48.211999], [16.371911, 48.208485], [16.368825, 48.201261]]]},
                   "properties": %s},
                  {"type": "Feature", "id": "22-2-j26-1",
                   "geometry": {"type": "MultiLineString", "coordinates": [
                     [[16.382746, 48.210509], [16.377715, 48.211771], [16.379398, 48.213914]]]},
                   "properties": %s}
                ]}
                """.formatted(U1, TRAM_2), JsonCompareMode.STRICT);
        // On the national holiday the weekday service makes way for the weekend's, which has no tram.
        client.get()
                .uri("/api/lines?date=2026-10-26")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.features[*].id")
                .isEqualTo(List.of("21-U1-j26-1"));
        // The U1 is halfway from Stephansplatz to Karlsplatz, and the tram waits at Schwedenplatz.
        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                {"type": "FeatureCollection", "features": [
                  {"type": "Feature", "id": "1.T2.21-U1-j26-1.6.R",
                   "geometry": {"type": "Point", "coordinates": [16.370368, 48.204873]},
                   "properties": {"line": "21-U1-j26-1", "headsign": "Wien Oberlaa", %s}},
                  {"type": "Feature", "id": "1.T2.22-2-j26-1.1.H",
                   "geometry": {"type": "Point", "coordinates": [16.377715, 48.211771]},
                   "properties": {"line": "22-2-j26-1", "headsign": "Wien Friedrich-Engels-Platz", %s}}
                ]}
                """.formatted(unwrapped(U1), unwrapped(TRAM_2)), JsonCompareMode.STRICT);
        // The U1 left Schwedenplatz at 08:01, and the next day's trips leave more than an hour later.
        client.get()
                .uri("/api/stops/at:49:1198/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"stop": {"id": "at:49:1198", "name": "Schwedenplatz"}, "liveStatus": "unavailable",
                         "departures": [
                          {"plannedTime": "2026-10-20T08:04:00+02:00", "liveTime": null,
                           "headsign": "Wien Friedrich-Engels-Platz", "platform": "at:49:1198:0:2",
                           "line": {"id": "22-2-j26-1", %s}}
                        ]}
                        """.formatted(unwrapped(TRAM_2)), JsonCompareMode.STRICT);
    }

    // The downloader relies on the JDK's client to stop an oversized or slow feed. Apache HttpClient
    // is there for the live departures, and Spring Boot would pick it by default.
    @Test
    void downloadsWithTheJdkClient(@Autowired ClientHttpRequestFactory requests) {
        assertThat(requests).isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void keepsTheTimetableWhenTheDownloadFails(CapturedOutput output) throws IOException {
        importFeed(TestFeed.fixture());
        var timetable = timetable();
        SERVER.fail(503);

        importJob.run();

        assertThat(output).contains("Feed import failed", "GTFS download failed with HTTP 503");
        assertThat(timetable()).isEqualTo(timetable);
    }

    @Test
    void keepsTheTimetableAndDeletesTheDownloadWhenTheFeedLacksAFile(CapturedOutput output) throws IOException {
        importFeed(TestFeed.fixture());
        var timetable = timetable();
        var files = changedFeed();
        files.remove("stop_times.txt");

        importFeed(files);

        assertThat(output).contains("Feed import failed", "GTFS ZIP is missing a file: stop_times.txt");
        assertThat(FeedServer.downloads()).isEmpty();
        assertThat(timetable()).isEqualTo(timetable);
    }

    @Test
    void keepsTheTimetableAndDeletesTheDownloadWhenTheDatabaseRejectsTheFeed(CapturedOutput output) throws IOException {
        importFeed(TestFeed.fixture());
        var timetable = timetable();
        var files = changedFeed();
        files.merge(
                "trips.txt",
                "\"22-2-j26-1\",\"T2\",\"1.T2.22-2-j26-1.1.H\",\"22-2-j26-1.1.H\",\"Wien Friedrich-Engels-Platz\",\"0\",\"\"\n",
                String::concat);

        importFeed(files);

        assertThat(output).contains("Feed import failed", "Key (id)=(1.T2.22-2-j26-1.1.H) already exists.");
        assertThat(FeedServer.downloads()).isEmpty();
        assertThat(timetable()).isEqualTo(timetable);
    }

    // The fixture with Karlsplatz renamed and the U1 recoloured, which the API would show if the
    // import went through.
    private static Map<String, String> changedFeed() {
        var files = TestFeed.fixture();
        files.replaceAll((file, content) ->
                content.replace("\"Karlsplatz\"", "\"Karlsplatz neu\"").replace("\"E3000F\"", "\"FF0000\""));
        return files;
    }

    // The line's properties without their braces, for the responses that unwrap them.
    private static String unwrapped(String properties) {
        return properties.substring(1, properties.length() - 1);
    }

    private void importFeed(Map<String, String> files) throws IOException {
        SERVER.serve(Files.readAllBytes(TestFeed.write(dir, files)));
        importJob.run();
    }

    // What the API answers from the timetable at the fixed clock.
    private List<String> timetable() {
        return Stream.of("/api/stops", "/api/lines", "/api/vehicles", "/api/stops/at:49:1198/departures")
                .map(path -> Objects.requireNonNull(
                        client.get()
                                .uri(path)
                                .exchange()
                                .expectStatus()
                                .isOk()
                                .returnResult(String.class)
                                .getResponseBody(),
                        path))
                .toList();
    }
}
