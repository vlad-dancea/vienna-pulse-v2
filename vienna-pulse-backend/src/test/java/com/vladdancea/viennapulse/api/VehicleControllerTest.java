package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
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

    // Each test sets the time, which the application reads through the clock bean.
    private static Clock now = Clock.systemUTC();

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ResponseCache cache;

    // Never read: the field only names the bean that clock() replaces.
    @TestBean
    @Nullable
    Clock clock;

    static Clock clock() {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return now.withZone(zone);
            }

            @Override
            public Instant instant() {
                return now.instant();
            }
        };
    }

    @BeforeEach
    void removeTheTimetable() {
        for (var table : List.of(
                "routes", "trips", "trip_spans", "stop_times", "shape_points", "service_calendars", "calendar_dates")) {
            jdbc.sql("DELETE FROM timetable." + table).update();
        }
        addRoute("u1", "U1", "Leopoldau - Oberlaa", "SUBWAY", "E3000F", "FFFFFF");
        addRoute("n25", "N25", null, "BUS", null, null);
        addShapePoint(SHAPE, 1, 16.0, 48.0, 0.0);
        addShapePoint(SHAPE, 2, 16.1, 48.0, 1000.0);
        addShapePoint(SHAPE, 3, 16.1, 48.1, 2000.0);
        cache.clear();
    }

    @Test
    void placesEveryRunningTripAlongItsShape() {
        // Five past midnight on DAY in Vienna.
        now = Clock.fixed(Instant.parse("2026-10-19T22:05:00Z"), ZoneOffset.UTC);
        addWeeklyService("mondays", "monday", DAY.minusDays(1));
        addWeeklyService("tuesdays", "tuesday", DAY);
        addWeeklyService("removed", "tuesday", DAY);
        addException("removed", DAY, "REMOVED");
        addException("added", DAY, "ADDED");

        // Halfway from the first stop to the second, 750 m east.
        addTrip(
                "moving",
                "u1",
                "tuesdays",
                SHAPE,
                call(1, null, "00:00:00", 0),
                call(2, "00:10:00", "00:12:00", 1500),
                call(3, "00:20:00", null, 2000));
        // Yesterday's trip waits at its second stop, round the corner.
        addTrip(
                "waiting",
                "n25",
                "mondays",
                SHAPE,
                call(1, null, "23:50:00", 0),
                call(2, "24:00:00", "24:06:00", 1500),
                call(3, "24:20:00", null, 2000));
        // A quarter of the way to the next timed stop, past the second, which has no times.
        addTrip(
                "untimed",
                "u1",
                "added",
                SHAPE,
                call(1, null, "00:00:00", 0),
                call(2, null, null, 1500),
                call(3, "00:20:00", null, 2000));
        // Halfway along a shape whose middle point has no distance, which is passed over.
        addShapePoint("gap", 1, 16.0, 48.2, 0.0);
        addShapePoint("gap", 2, 16.0, 48.3, null);
        addShapePoint("gap", 3, 16.2, 48.2, 2000.0);
        addTrip("gap", "u1", "tuesdays", "gap", call(1, null, "00:00:00", 0), call(2, "00:10:00", null, 2000));
        addTrip("not-yet", "u1", "tuesdays", SHAPE, call(1, null, "00:10:00", 0), call(2, "00:20:00", null, 2000));
        addTrip("ended", "u1", "mondays", SHAPE, call(1, null, "23:40:00", 0), call(2, "24:00:00", null, 2000));
        addTrip("removed", "u1", "removed", SHAPE, call(1, null, "00:00:00", 0), call(2, "00:20:00", null, 2000));
        addTrip("no-shape", "u1", "tuesdays", null, call(1, null, "00:00:00", 0), call(2, "00:20:00", null, 2000));

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
        now = Clock.fixed(Instant.parse("2026-03-28T22:30:00Z"), ZoneOffset.UTC);
        addWeeklyService("sundays", "sunday", LocalDate.parse("2026-03-29"));
        addTrip("sunday", "u1", "sundays", SHAPE, call(1, null, "00:00:00", 0), call(2, "01:00:00", null, 2000));

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
        addWeeklyService("tuesdays", "tuesday", DAY);
        // A meter a second along the first leg of the shape.
        addTrip("first", "u1", "tuesdays", SHAPE, call(1, null, "00:00:00", 0), call(2, "00:33:20", null, 2000));
        // Five past midnight on DAY in Vienna, 300 m east.
        now = Clock.fixed(Instant.parse("2026-10-19T22:05:00Z"), ZoneOffset.UTC);
        var etag = client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult()
                .getResponseHeaders()
                .getETag();

        // Within the step, the trip added and the meters moved since are not read.
        now = Clock.fixed(Instant.parse("2026-10-19T22:05:04Z"), ZoneOffset.UTC);
        addTrip("second", "u1", "tuesdays", SHAPE, call(1, null, "00:00:00", 0), call(2, "00:33:20", null, 2000));
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
        now = Clock.fixed(Instant.parse("2026-10-19T22:05:06Z"), ZoneOffset.UTC);
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

    // A call of a trip, with times written as in the feed, where hours can pass 24.
    private record Call(
            int sequence,
            @Nullable Integer arrival,
            @Nullable Integer departure,
            double distance) {}

    private static Call call(int sequence, @Nullable String arrival, @Nullable String departure, double distance) {
        return new Call(sequence, seconds(arrival), seconds(departure), distance);
    }

    private static @Nullable Integer seconds(@Nullable String time) {
        if (time == null) {
            return null;
        }
        var parts = time.split(":", -1);
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
    }

    // A trip with its headsign named after it, and the span the import would derive from its calls.
    private void addTrip(String id, String route, String service, @Nullable String shape, Call... calls) {
        jdbc.sql("""
                        INSERT INTO timetable.trips (id, route_id, service_id, shape_id, headsign)
                        VALUES (:id, :route, :service, :shape, :id)
                        """)
                .param("id", id)
                .param("route", route)
                .param("service", service)
                .param("shape", shape)
                .update();
        for (var call : calls) {
            jdbc.sql("""
                            INSERT INTO timetable.stop_times
                                (trip_id, sequence, stop_id, arrival_time, departure_time, pickup_type, drop_off_type,
                                 distance_traveled)
                            VALUES (:trip, :sequence, 'stop', :arrival, :departure, 'REGULAR', 'REGULAR', :distance)
                            """)
                    .param("trip", id)
                    .param("sequence", call.sequence())
                    .param("arrival", call.arrival())
                    .param("departure", call.departure())
                    .param("distance", call.distance())
                    .update();
        }
        jdbc.sql("INSERT INTO timetable.trip_spans VALUES (:trip, :service, :first, :last)")
                .param("trip", id)
                .param("service", service)
                .param("first", calls[0].departure())
                .param("last", calls[calls.length - 1].arrival())
                .update();
    }

    private void addShapePoint(
            String shape, int sequence, double longitude, double latitude, @Nullable Double distance) {
        jdbc.sql("INSERT INTO timetable.shape_points VALUES (:shape, :sequence, :latitude, :longitude, :distance)")
                .param("shape", shape)
                .param("sequence", sequence)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("distance", distance)
                .update();
    }

    private void addRoute(
            String id,
            String shortName,
            @Nullable String longName,
            String type,
            @Nullable String color,
            @Nullable String textColor) {
        jdbc.sql("""
                        INSERT INTO timetable.routes (id, short_name, long_name, type, color, text_color)
                        VALUES (:id, :shortName, :longName, :type, :color, :textColor)
                        """)
                .param("id", id)
                .param("shortName", shortName)
                .param("longName", longName)
                .param("type", type)
                .param("color", color)
                .param("textColor", textColor)
                .update();
    }

    // Runs on one weekday from a week before `day` to a week after.
    private void addWeeklyService(String id, String weekday, LocalDate day) {
        jdbc.sql("""
                        INSERT INTO timetable.service_calendars
                        VALUES (:id, false, false, false, false, false, false, false, :start, :end)
                        """)
                .param("id", id)
                .param("start", day.minusDays(7))
                .param("end", day.plusDays(7))
                .update();
        jdbc.sql("UPDATE timetable.service_calendars SET %s = true WHERE service_id = :id".formatted(weekday))
                .param("id", id)
                .update();
    }

    private void addException(String service, LocalDate date, String type) {
        jdbc.sql("INSERT INTO timetable.calendar_dates VALUES (?, ?, ?)")
                .params(service, date, type)
                .update();
    }
}
