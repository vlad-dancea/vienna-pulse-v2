package com.vladdancea.viennapulse.api;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.importer.read.calendar.CalendarDate;
import com.vladdancea.viennapulse.importer.read.calendar.ServiceCalendar;
import com.vladdancea.viennapulse.importer.read.route.Route;
import com.vladdancea.viennapulse.importer.read.shape.ShapePoint;
import com.vladdancea.viennapulse.importer.read.stop.Stop;
import com.vladdancea.viennapulse.importer.read.trip.Trip;
import com.vladdancea.viennapulse.importer.store.Store;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

// A real server on a random port, so the ETag filter and Tomcat's compression take part.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class StopControllerTest {
    @Autowired
    RestTestClient client;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ResponseCache cache;

    @Autowired
    Store store;

    @LocalServerPort
    int port;

    @BeforeEach
    void removeAllStops() {
        jdbc.sql("DELETE FROM timetable.stops").update();
        jdbc.sql("DELETE FROM timetable.stop_modes").update();
        cache.clear();
    }

    @Test
    void servesEveryStopAsGeoJson() {
        addStop("s2", "Stephansplatz", 48.20845, 16.37212);
        addStop("s1", "Karlsplatz", 48.20024, 16.36951);

        // Ordered by id, and longitude before latitude.
        client.get()
                .uri("/api/stops")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType("application/geo+json")
                .expectHeader()
                .cacheControl(CacheControl.noCache())
                .expectBody()
                .json("""
                        {"type": "FeatureCollection", "features": [
                          {"type": "Feature", "id": "s1",
                           "geometry": {"type": "Point", "coordinates": [16.36951, 48.20024]},
                           "properties": {"name": "Karlsplatz", "modes": null}},
                          {"type": "Feature", "id": "s2",
                           "geometry": {"type": "Point", "coordinates": [16.37212, 48.20845]},
                           "properties": {"name": "Stephansplatz", "modes": null}}
                        ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void mergesThePlatformsOfAStopIntoOnePointInTheirMiddle() {
        // Ids and names from the feed; coordinates whose averages are exact in floating point.
        addStop("at:49:1349:0:1", "Hauptbahnhof", 48.25, 16.25);
        addStop("at:49:1349:0:2", "Südtiroler Pl. - Hbf.", 48.5, 16.5);
        addStop("at:49:1349:0:3", "Hauptbahnhof", 48.75, 16.75);
        addStop("at:49:1320:0:1", "Stephansplatz", 48.20845, 16.37212);

        client.get()
                .uri("/api/stops")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"type": "FeatureCollection", "features": [
                          {"type": "Feature", "id": "at:49:1320",
                           "geometry": {"type": "Point", "coordinates": [16.37212, 48.20845]},
                           "properties": {"name": "Stephansplatz", "modes": null}},
                          {"type": "Feature", "id": "at:49:1349",
                           "geometry": {"type": "Point", "coordinates": [16.5, 48.5]},
                           "properties": {"name": "Hauptbahnhof", "modes": null}}
                        ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void combinesModesWithoutWeightingTheNameOrCentroidByModeCount() {
        addStop("at:49:1349:0:1", "Hauptbahnhof", 48.25, 16.25);
        addStop("at:49:1349:0:2", "Südtiroler Pl. - Hbf.", 48.5, 16.5);
        addStop("at:49:1349:0:3", "Hauptbahnhof", 48.75, 16.75);
        addStop("unused", "Unused", 48.0, 16.0);
        jdbc.sql("""
                INSERT INTO timetable.stop_modes VALUES
                ('at:49:1349:0:1', ARRAY['TRAM']),
                ('at:49:1349:0:2', ARRAY['TRAM', 'SUBWAY', 'BUS']),
                ('at:49:1349:0:3', ARRAY['SUBWAY']),
                ('unused', ARRAY[]::text[])
                """).update();
        client.get()
                .uri("/api/stops")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","id":"at:49:1349",
                   "geometry":{"type":"Point","coordinates":[16.5,48.5]},
                   "properties":{"name":"Hauptbahnhof","modes":["bus","subway","tram"]}},
                  {"type":"Feature","id":"unused",
                   "geometry":{"type":"Point","coordinates":[16.0,48.0]},
                   "properties":{"name":"Unused","modes":[]}}
                ]}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void servesPlainJsonToClientsThatOnlyAcceptIt() {
        addStop("s1", "Karlsplatz", 48.20024, 16.36951);

        // What MapLibre sends when it loads a GeoJSON source.
        client.get()
                .uri("/api/stops")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void answersNotModifiedWhileTheStopsAreUnchanged() {
        addStop("s1", "Karlsplatz", 48.20024, 16.36951);
        var etag = etag();

        client.get()
                .uri("/api/stops")
                .ifNoneMatch(etag)
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectBody()
                .isEmpty();
    }

    @Test
    void readsTheStopsOncePerTimetable() throws IOException {
        addStop("s1", "Karlsplatz", 48.20024, 16.36951);
        var before = etag();

        // Only an import changes the stops, so a stop added behind its back is not read.
        addStop("s2", "Stephansplatz", 48.20845, 16.37212);
        client.get()
                .uri("/api/stops")
                .ifNoneMatch(before)
                .exchange()
                .expectStatus()
                .isNotModified();

        importNothing();
        addStop("s3", "Schwedenplatz", 48.21178, 16.37798);
        client.get()
                .uri("/api/stops")
                .ifNoneMatch(before)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"type": "FeatureCollection", "features": [
                          {"type": "Feature", "id": "s3",
                           "geometry": {"type": "Point", "coordinates": [16.37798, 48.21178]},
                           "properties": {"name": "Schwedenplatz", "modes": null}}
                        ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void compressesTheResponse() throws IOException, InterruptedException {
        // Tomcat leaves responses under 2 KB uncompressed.
        for (int i = 0; i < 50; i++) {
            addStop("s" + i, "Stop " + i, 48.2, 16.37);
        }
        // The test client unpacks compressed responses and drops the header, so the JDK client asks.
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stops"))
                .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                .build();

        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, BodyHandlers.discarding());
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_ENCODING))
                    .hasValue("gzip");
        }
    }

    private void addStop(String id, String name, double latitude, double longitude) {
        jdbc.sql("INSERT INTO timetable.stops (id, name, latitude, longitude) VALUES (?, ?, ?, ?)")
                .params(id, name, latitude, longitude)
                .update();
    }

    // Swaps in an empty timetable the way the daily import swaps in the next one.
    private void importNothing() throws IOException {
        store.replace(staging -> {
            staging.copy("routes", Route.class, row -> {});
            staging.copy("stops", Stop.class, row -> {});
            staging.copy("shape_points", ShapePoint.class, row -> {});
            staging.copy("trips", Trip.class, row -> {});
            staging.copy("service_calendars", ServiceCalendar.class, row -> {});
            staging.copy("calendar_dates", CalendarDate.class, row -> {});
            staging.stopTimes(row -> {});
        });
    }

    private String etag() {
        var headers = client.get()
                .uri("/api/stops")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult()
                .getResponseHeaders();
        return requireNonNull(headers.getETag(), "The response has no ETag");
    }
}
