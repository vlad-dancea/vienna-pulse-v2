package com.vladdancea.viennapulse.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.vladdancea.viennapulse.TestClock;
import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.TimetableFixture;
import com.vladdancea.viennapulse.TimetableFixture.Call;
import com.vladdancea.viennapulse.importer.read.calendar.ExceptionType;
import com.vladdancea.viennapulse.importer.read.route.RouteType;
import com.vladdancea.viennapulse.importer.read.stoptime.PickupDropOffType;
import com.vladdancea.viennapulse.live.LiveDepartures;
import com.vladdancea.viennapulse.live.MonitorServer;
import com.vladdancea.viennapulse.live.Platforms;
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

    @Autowired
    LiveDepartures live;

    @Autowired
    Platforms platforms;

    @Autowired
    MonitorServer monitor;

    @BeforeEach
    void removeTheTimetable() {
        platforms.load();
        // A round forgets the stations nobody opened in the minute before it, so one far ahead
        // forgets those of other tests.
        clock.set("2100-01-01T00:00:00Z");
        live.round();
        monitor.reset();
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
        // Halfway from the first stop to the one at 1,800 m, past two without distances, which it
        // passes like those without times.
        timetable.addTrip(
                "unmeasured",
                "u1",
                "tuesdays",
                SHAPE,
                call(1, null, "00:00:00", 0),
                new Call(2, "stop", "00:04:00", "00:04:00", PickupDropOffType.REGULAR, null),
                new Call(3, "stop", "00:05:00", "00:05:00", PickupDropOffType.REGULAR, null),
                call(4, "00:06:00", "00:06:00", 1800),
                call(5, "00:07:00", null, 2000));
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
                        {"type": "FeatureCollection", "time": "2026-10-19T22:05:00Z", "features": [
                          {"type": "Feature", "id": "gap",
                           "geometry": {"type": "Point", "coordinates": [16.1, 48.2]},
                           "properties": {"line": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa",
                                          "type": "subway", "color": "#E3000F", "textColor": "#FFFFFF",
                                          "headsign": "gap", "delay": null,
                                          "path": [[16.1, 48.2, 0.0], [16.11, 48.2, 30.0]]}},
                          {"type": "Feature", "id": "moving",
                           "geometry": {"type": "Point", "coordinates": [16.075, 48.0]},
                           "properties": {"line": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa",
                                          "type": "subway", "color": "#E3000F", "textColor": "#FFFFFF",
                                          "headsign": "moving", "delay": null,
                                          "path": [[16.075, 48.0, 0.0], [16.0825, 48.0, 30.0]]}},
                          {"type": "Feature", "id": "unmeasured",
                           "geometry": {"type": "Point", "coordinates": [16.1, 48.05]},
                           "properties": {"line": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa",
                                          "type": "subway", "color": "#E3000F", "textColor": "#FFFFFF",
                                          "headsign": "unmeasured", "delay": null,
                                          "path": [[16.1, 48.05, 0.0], [16.1, 48.065, 30.0]]}},
                          {"type": "Feature", "id": "untimed",
                           "geometry": {"type": "Point", "coordinates": [16.05, 48.0]},
                           "properties": {"line": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa",
                                          "type": "subway", "color": "#E3000F", "textColor": "#FFFFFF",
                                          "headsign": "untimed", "delay": null,
                                          "path": [[16.05, 48.0, 0.0], [16.055, 48.0, 30.0]]}},
                          {"type": "Feature", "id": "waiting",
                           "geometry": {"type": "Point", "coordinates": [16.1, 48.05]},
                           "properties": {"line": "n25", "name": "N25", "longName": null, "type": "bus",
                                          "color": null, "textColor": null, "headsign": "waiting", "delay": null,
                                          "path": [[16.1, 48.05, 0.0], [16.1, 48.05, 30.0]]}}
                        ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void followsTheShapeAndWaitsAtStopsForThirtySeconds() {
        timetable.addWeeklyService("tuesdays", DAY);
        // The L, with a point just before the corner at the corner's distance, as the feed has now and
        // then, and a point halfway between the corner and a stop, which the path passes anyway.
        timetable.addShapePoint("corner", 1, 16.0, 48.0, 0.0);
        timetable.addShapePoint("corner", 2, 16.099, 48.0, 1000.0);
        timetable.addShapePoint("corner", 3, 16.1, 48.0, 1000.0);
        timetable.addShapePoint("corner", 4, 16.1, 48.005, 1050.0);
        timetable.addShapePoint("corner", 5, 16.1, 48.1, 2000.0);
        // 10 m a second, 50 m before the corner, until it waits at a stop 100 m past it.
        timetable.addTrip(
                "corner",
                "u1",
                "tuesdays",
                "corner",
                call(1, null, "00:03:25", 0),
                call(2, "00:05:15", "00:05:25", 1100),
                call(3, "00:06:55", null, 2000));
        // Reaches the end of the shape and of its trip 20 seconds from now.
        timetable.addTrip(
                "ending", "u1", "tuesdays", SHAPE, call(1, null, "00:04:20", 1600), call(2, "00:05:20", null, 2000));

        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectBody()
                .jsonPath("$.features[*].properties.path")
                .isEqualTo(List.of(
                        List.of(
                                List.of(16.095, 48.0, 0.0),
                                List.of(16.1, 48.0, 5.0),
                                List.of(16.1, 48.01, 15.0),
                                List.of(16.1, 48.01, 25.0),
                                List.of(16.1, 48.015, 30.0)),
                        List.of(List.of(16.1, 48.086667, 0.0), List.of(16.1, 48.1, 20.0))));
    }

    @Test
    void placesAVehicleTheSameWayInAnswersFifteenSecondsApart() {
        timetable.addWeeklyService("tuesdays", DAY);
        // Two points share a distance just past the stop at 900 m, the end of the first answer's calls.
        timetable.addShapePoint("repeat", 1, 16.0, 48.2, 0.0);
        timetable.addShapePoint("repeat", 2, 16.01, 48.2, 1000.0);
        timetable.addShapePoint("repeat", 3, 16.0115, 48.2, 1000.0);
        timetable.addShapePoint("repeat", 4, 16.02, 48.2, 2000.0);
        timetable.addTrip(
                "repeat",
                "u1",
                "tuesdays",
                "repeat",
                call(1, null, "00:05:00", 0),
                call(2, "00:06:00", "00:06:00", 500),
                call(3, "00:07:00", "00:07:00", 900),
                call(4, "00:08:00", null, 2000));

        // The second answer starts where the first one is halfway, towards the last of the two points.
        clock.set("2026-10-19T22:05:20Z");
        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectBody()
                .jsonPath("$.features[*].properties.path")
                .isEqualTo(List.of(List.of(List.of(16.001917, 48.2, 0.0), List.of(16.004792, 48.2, 30.0))));
        clock.set("2026-10-19T22:05:35Z");
        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectBody()
                .jsonPath("$.features[*].properties.path")
                .isEqualTo(List.of(List.of(
                        List.of(16.003354, 48.2, 0.0), List.of(16.00575, 48.2, 25.0), List.of(16.006133, 48.2, 30.0))));
    }

    @Test
    void sharesALegWithoutTimeWithTheLegsAroundIt() {
        // Ten seconds after the first trip reached the stops at 600 m and 1,000 m, both at 00:05.
        clock.set("2026-10-19T22:05:10Z");
        timetable.addWeeklyService("tuesdays", DAY);
        timetable.addTrip(
                "rounded",
                "u1",
                "tuesdays",
                SHAPE,
                call(1, null, "00:04:00", 0),
                call(2, "00:05:00", "00:05:00", 600),
                call(3, "00:05:00", "00:05:00", 1000),
                call(4, "00:06:00", null, 1400));
        // Reaches its last two stops at 00:06, after the path ends.
        timetable.addTrip(
                "ending",
                "u1",
                "tuesdays",
                SHAPE,
                call(1, null, "00:04:00", 0),
                call(2, "00:06:00", "00:06:00", 600),
                call(3, "00:06:00", null, 1000));

        // Each is halfway between the two stops at their minute.
        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectBody()
                .jsonPath("$.features[*].properties.path")
                .isEqualTo(List.of(
                        List.of(List.of(16.046667, 48.0, 0.0), List.of(16.066667, 48.0, 30.0)),
                        List.of(List.of(16.09, 48.0, 0.0), List.of(16.1, 48.0, 10.0), List.of(16.1, 48.02, 30.0))));
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

    // The fake monitor's answer came from Praterstern at 21:47:55 on Saturday 2026-10-24, when Wiener
    // Linien expected the U1 towards Oberlaa to leave 34 seconds after the timetable's 21:49:00. The
    // board opens the station, and the next round pairs the answer with the board's timetable.
    @Test
    void movesAVehicleGraduallyToWhereTheLiveTimeOfAnOpenStationPutsIt() {
        clock.set(MonitorServer.ANSWERED_AT);
        timetable.addWeeklyService("saturdays", LocalDate.parse("2026-10-24"));
        timetable.addStop("at:49:1040:0:1", "Praterstern");
        // 250 m a minute along the L, round the corner at Praterstern.
        timetable.addTripTowards(
                "u1-oberlaa",
                "Wien Oberlaa",
                1,
                "u1",
                "saturdays",
                SHAPE,
                call(1, null, "21:45:00", 0),
                new Call(2, "at:49:1040:0:1", "21:49:00", "21:49:00", PickupDropOffType.REGULAR, 1000.0),
                call(3, "21:53:00", null, 2000));
        client.get()
                .uri("/api/stops/at:49:1040/departures")
                .exchange()
                .expectStatus()
                .isOk();
        live.round();

        // The delay changes only once the paths that maps already have end, and then by half a second
        // each second, so the vehicle slows down to half its speed until it is 34 seconds behind.
        assertThat(vehicleAt("2026-10-24T19:48:00Z"))
                .isEqualTo(new Vehicle(null, List.of(List.of(16.075, 48.0, 0.0), List.of(16.0875, 48.0, 30.0))));
        assertThat(vehicleAt("2026-10-24T19:48:30Z"))
                .isEqualTo(new Vehicle(0, List.of(List.of(16.0875, 48.0, 0.0), List.of(16.09375, 48.0, 30.0))));
        assertThat(vehicleAt("2026-10-24T19:49:30Z"))
                .isEqualTo(new Vehicle(
                        30,
                        List.of(
                                List.of(16.1, 48.0, 0.0),
                                List.of(16.1, 48.001667, 8.0),
                                List.of(16.1, 48.010833, 30.0))));
        assertThat(vehicleAt("2026-10-24T19:50:00Z"))
                .isEqualTo(new Vehicle(34, List.of(List.of(16.1, 48.010833, 0.0), List.of(16.1, 48.023333, 30.0))));
        // The timetable ends the trip at 21:53, and the vehicle 34 seconds later.
        assertThat(vehicleAt("2026-10-24T19:53:05Z"))
                .isEqualTo(new Vehicle(34, List.of(List.of(16.1, 48.087917, 0.0), List.of(16.1, 48.1, 29.0))));
        // Working out the vehicles asked Wiener Linien nothing.
        assertThat(monitor.requests()).hasSize(2);
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
                        {"type": "FeatureCollection", "time": "2026-10-19T22:05:00Z", "features": []}
                        """, JsonCompareMode.STRICT);
    }

    // The one vehicle of the answer at `instant`, by its delay and path.
    private Vehicle vehicleAt(String instant) {
        clock.set(instant);
        var body = client.get()
                .uri("/api/vehicles")
                .exchange()
                .returnResult(String.class)
                .getResponseBody();
        return new Vehicle(
                JsonPath.read(body, "$.features[0].properties.delay"),
                JsonPath.read(body, "$.features[0].properties.path"));
    }

    private record Vehicle(@Nullable Integer delay, List<List<Double>> path) {}

    // A call at a stop the test does not need, `distance` metres along the trip's shape.
    private static Call call(int sequence, @Nullable String arrival, @Nullable String departure, double distance) {
        return new Call(sequence, "stop", arrival, departure, PickupDropOffType.REGULAR, distance);
    }
}
