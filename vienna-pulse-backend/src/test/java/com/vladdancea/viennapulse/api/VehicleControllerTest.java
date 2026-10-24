package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.TestClock;
import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.TimetableFixture;
import com.vladdancea.viennapulse.TimetableFixture.Call;
import com.vladdancea.viennapulse.importer.read.calendar.ExceptionType;
import com.vladdancea.viennapulse.importer.read.route.RouteType;
import com.vladdancea.viennapulse.importer.read.stoptime.PickupDropOffType;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class VehicleControllerTest {
    // A Tuesday.
    private static final LocalDate DAY = LocalDate.parse("2026-10-20");
    // An L along the shape's three points: 1,000 m east, then 1,000 m north.
    private static final String SHAPE = "l-shape";

    @Autowired
    RestTestClient client;

    @Autowired
    TimetableFixture timetable;

    @Autowired
    TestClock clock;

    @BeforeEach
    void removeTheTimetable() {
        // Five past midnight on DAY in Vienna.
        clock.set("2026-10-19T22:05:00Z");
        timetable.clear();
        timetable.addRoute("u1", "U1", "Leopoldau - Oberlaa", RouteType.SUBWAY, "E3000F", "FFFFFF");
        timetable.addRoute("n25", "N25", null, RouteType.BUS, null, null);
        timetable.addShapePoint(SHAPE, 1, 16.0, 48.0, 0.0);
        timetable.addShapePoint(SHAPE, 2, 16.1, 48.0, 1000.0);
        timetable.addShapePoint(SHAPE, 3, 16.1, 48.1, 2000.0);
    }

    @Test
    void placesEveryRunningTripAlongItsShape() {
        timetable.addWeeklyService("mondays", DAY.minusDays(1));
        timetable.addWeeklyService("tuesdays", DAY);
        timetable.addWeeklyService("removed", DAY);
        timetable.addException("removed", DAY, ExceptionType.REMOVED);
        timetable.addException("added", DAY, ExceptionType.ADDED);

        // Halfway from the first stop to the second, 750 m east.
        timetable.addTrip(
                "moving",
                "u1",
                "tuesdays",
                SHAPE,
                call(1, null, "00:00:00", 0),
                call(2, "00:10:00", "00:12:00", 1500),
                call(3, "00:20:00", null, 2000));
        // Yesterday's trip waits at its second stop, round the corner.
        timetable.addTrip(
                "waiting",
                "n25",
                "mondays",
                SHAPE,
                call(1, null, "23:50:00", 0),
                call(2, "24:00:00", "24:06:00", 1500),
                call(3, "24:20:00", null, 2000));
        // A quarter of the way to the next timed stop, past the second, which has no times.
        timetable.addTrip(
                "untimed",
                "u1",
                "added",
                SHAPE,
                call(1, null, "00:00:00", 0),
                call(2, null, null, 1500),
                call(3, "00:20:00", null, 2000));
        // Halfway along a shape whose middle point has no distance, which is passed over.
        timetable.addShapePoint("gap", 1, 16.0, 48.2, 0.0);
        timetable.addShapePoint("gap", 2, 16.0, 48.3, null);
        timetable.addShapePoint("gap", 3, 16.2, 48.2, 2000.0);
        timetable.addTrip(
                "gap", "u1", "tuesdays", "gap", call(1, null, "00:00:00", 0), call(2, "00:10:00", null, 2000));
        timetable.addTrip(
                "not-yet", "u1", "tuesdays", SHAPE, call(1, null, "00:10:00", 0), call(2, "00:20:00", null, 2000));
        timetable.addTrip(
                "ended", "u1", "mondays", SHAPE, call(1, null, "23:40:00", 0), call(2, "24:00:00", null, 2000));
        timetable.addTrip(
                "removed", "u1", "removed", SHAPE, call(1, null, "00:00:00", 0), call(2, "00:20:00", null, 2000));
        timetable.addTrip(
                "no-shape", "u1", "tuesdays", null, call(1, null, "00:00:00", 0), call(2, "00:20:00", null, 2000));

        client.get()
                .uri("/api/vehicles")
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
                          {"type": "Feature", "id": "gap",
                           "geometry": {"type": "Point", "coordinates": [16.1, 48.2]},
                           "properties": {"line": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa",
                                          "type": "subway", "color": "#E3000F", "textColor": "#FFFFFF",
                                          "headsign": "gap"}},
                          {"type": "Feature", "id": "moving",
                           "geometry": {"type": "Point", "coordinates": [16.075, 48.0]},
                           "properties": {"line": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa",
                                          "type": "subway", "color": "#E3000F", "textColor": "#FFFFFF",
                                          "headsign": "moving"}},
                          {"type": "Feature", "id": "untimed",
                           "geometry": {"type": "Point", "coordinates": [16.05, 48.0]},
                           "properties": {"line": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa",
                                          "type": "subway", "color": "#E3000F", "textColor": "#FFFFFF",
                                          "headsign": "untimed"}},
                          {"type": "Feature", "id": "waiting",
                           "geometry": {"type": "Point", "coordinates": [16.1, 48.05]},
                           "properties": {"line": "n25", "name": "N25", "longName": null, "type": "bus",
                                          "color": null, "textColor": null, "headsign": "waiting"}}
                        ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void placesTomorrowsTripsOnTheEveningBeforeTheClocksGoForward() {
        // 23:30 on Saturday 2026-03-28, already 00:30 on Sunday's service day.
        clock.set("2026-03-28T22:30:00Z");
        timetable.addWeeklyService("sundays", LocalDate.parse("2026-03-29"));
        timetable.addTrip(
                "sunday", "u1", "sundays", SHAPE, call(1, null, "00:00:00", 0), call(2, "01:00:00", null, 2000));

        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.features[*].geometry.coordinates")
                .isEqualTo(List.of(List.of(16.1, 48.0)));
    }

    @Test
    void placesTheVehiclesOncePerStep() {
        timetable.addWeeklyService("tuesdays", DAY);
        // A meter a second along the first leg of the shape.
        timetable.addTrip(
                "first", "u1", "tuesdays", SHAPE, call(1, null, "00:00:00", 0), call(2, "00:33:20", null, 2000));
        // At five past midnight, 300 m east.
        var etag = client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult()
                .getResponseHeaders()
                .getETag();

        // Within the step, the trip added and the meters moved since are not read.
        clock.set("2026-10-19T22:05:04Z");
        timetable.addTrip(
                "second", "u1", "tuesdays", SHAPE, call(1, null, "00:00:00", 0), call(2, "00:33:20", null, 2000));
        client.get()
                .uri("/api/vehicles")
                .ifNoneMatch(String.valueOf(etag))
                .exchange()
                .expectStatus()
                .isNotModified();
        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectBody()
                .jsonPath("$.features[*].geometry.coordinates")
                .isEqualTo(List.of(List.of(16.03, 48.0)));

        // The next step reads them again, where they are at its start.
        clock.set("2026-10-19T22:05:06Z");
        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectBody()
                .jsonPath("$.features[*].geometry.coordinates")
                .isEqualTo(List.of(List.of(16.0305, 48.0), List.of(16.0305, 48.0)));
    }

    @Test
    void servesNoVehiclesWhenNothingRuns() {
        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"type": "FeatureCollection", "features": []}
                        """, JsonCompareMode.STRICT);
    }

    // A call at a stop the test does not need, `distance` metres along the trip's shape.
    private static Call call(int sequence, @Nullable String arrival, @Nullable String departure, double distance) {
        return new Call(sequence, "stop", arrival, departure, PickupDropOffType.REGULAR, distance);
    }
}
