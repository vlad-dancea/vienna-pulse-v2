package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.TestClock;
import com.vladdancea.viennapulse.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class LineControllerTest {
    // A Tuesday.
    private static final LocalDate DAY = LocalDate.parse("2026-10-20");

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestClock clock;

    @BeforeEach
    void removeTheTimetable() {
        // Half past midnight on DAY in Vienna, still the day before in UTC.
        clock.set("2026-10-19T22:30:00Z");
        for (var table : Set.of("routes", "trips", "shape_points", "service_calendars", "calendar_dates")) {
            jdbc.sql("DELETE FROM timetable." + table).update();
        }
    }

    @Test
    void servesEachRunningRouteWithTheDistinctPathsOfItsShapes() {
        addWeeklyService("weekdays", "tuesday", DAY, DAY);
        addWeeklyService("sundays", "sunday", DAY, DAY);
        addRoute("tram-62", "62", "Oper - Lainz", "TRAM", "C00808", "FFFFFF");
        addRoute("bus-n25", "N25", null, "BUS", null, null);
        addRoute("subway-u1", "U1", "Leopoldau - Oberlaa", "SUBWAY", "E3000F", "FFFFFF");

        // Two shapes with the same points, rounded to six decimals, make one path.
        addShape("62-b", 16.1234561, 48.1, 16.2, 48.2);
        addShape("62-a", 16.1234559, 48.1, 16.2, 48.2);
        addShape("62-c", 16.3, 48.3, 16.4, 48.4);
        addTrip("t1", "tram-62", "weekdays", "62-c");
        addTrip("t2", "tram-62", "weekdays", "62-b");
        addTrip("t3", "tram-62", "weekdays", "62-a");
        addTrip("t4", "tram-62", "weekdays", "62-a");
        // A shape of a single point is no line.
        addShape("n25-a", 16.5, 48.5);
        addShape("n25-b", 16.5, 48.5, 16.6, 48.6);
        addTrip("t5", "bus-n25", "weekdays", "n25-a");
        addTrip("t6", "bus-n25", "weekdays", "n25-b");
        // Runs on Sundays only.
        addShape("u1", 16.7, 48.7, 16.8, 48.8);
        addTrip("t7", "subway-u1", "sundays", "u1");
        // Runs, but along no shape of two points or more, so there is nothing to draw.
        addRoute("bus-13a", "13A", null, "BUS", null, null);
        addTrip("t8", "bus-13a", "weekdays", null);
        addTrip("t9", "bus-13a", "weekdays", "n25-a");

        client.get()
                .uri("/api/lines?date=2026-10-20")
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
                          {"type": "Feature", "id": "bus-13a",
                           "geometry": {"type": "MultiLineString", "coordinates": []},
                           "properties": {"name": "13A", "longName": null, "type": "bus",
                                          "color": null, "textColor": null}},
                          {"type": "Feature", "id": "bus-n25",
                           "geometry": {"type": "MultiLineString", "coordinates": [
                             [[16.5, 48.5], [16.6, 48.6]]
                           ]},
                           "properties": {"name": "N25", "longName": null, "type": "bus",
                                          "color": null, "textColor": null}},
                          {"type": "Feature", "id": "tram-62",
                           "geometry": {"type": "MultiLineString", "coordinates": [
                             [[16.123456, 48.1], [16.2, 48.2]],
                             [[16.3, 48.3], [16.4, 48.4]]
                           ]},
                           "properties": {"name": "62", "longName": "Oper - Lainz", "type": "tram",
                                          "color": "#C00808", "textColor": "#FFFFFF"}}
                        ]}
                        """, JsonCompareMode.STRICT);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"missing", "single"})
    void keepsARunningRouteWithNoUsablePath(@Nullable String shape) {
        addWeeklyService("weekdays", "tuesday", DAY, DAY);
        addRoute("bus-13a", "13A", null, "BUS", null, null);
        addShape("single", 16.5, 48.5);
        addTrip("trip", "bus-13a", "weekdays", shape);

        client.get()
                .uri("/api/lines?date=2026-10-20")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"type": "FeatureCollection", "features": [
                          {"type": "Feature", "id": "bus-13a",
                           "geometry": {"type": "MultiLineString", "coordinates": []},
                           "properties": {"name": "13A", "longName": null, "type": "bus",
                                          "color": null, "textColor": null}}
                        ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void runsTheServicesOfTheDayFromTheirWeeklyDaysAndExceptions() {
        addShape("shape", 16.1, 48.1, 16.2, 48.2);
        addRunningService("tuesdays", "tuesday", DAY, DAY);
        addRunningService("wednesdays", "wednesday", DAY.minusDays(7), DAY.plusDays(7));
        addRunningService("ended", "tuesday", DAY.minusDays(7), DAY.minusDays(1));
        addRunningService("removed", "tuesday", DAY.minusDays(7), DAY.plusDays(7));
        addException("removed", DAY, "REMOVED");
        addRunningService("added", "wednesday", DAY.minusDays(7), DAY.plusDays(7));
        addException("added", DAY, "ADDED");
        addRoute("only-added", "only-added", null, "BUS", null, null);
        addTrip("only-added", "only-added", "only-added", "shape");
        addException("only-added", DAY, "ADDED");

        client.get()
                .uri("/api/lines?date=2026-10-20")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.features[*].id")
                .isEqualTo(List.of("added", "only-added", "tuesdays"));
    }

    @Test
    void readsTheLinesOfTodayInViennaWithoutADate() {
        addShape("shape", 16.1, 48.1, 16.2, 48.2);
        addRoute("night", "N6", null, "BUS", null, null);
        addTrip("night", "night", "today", "shape");
        addException("today", DAY, "ADDED");

        client.get()
                .uri("/api/lines")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.features[*].id")
                .isEqualTo(List.of("night"));
    }

    @Test
    void answersNotFoundForADayTheTimetableDoesNotCover() {
        expectNotFound("2026-10-20", "There is no timetable yet");

        addWeeklyService("weekdays", "tuesday", DAY, DAY.plusDays(4));
        // A day added after the last week counts, a day removed before the first does not.
        addException("weekdays", DAY.plusDays(10), "ADDED");
        addException("weekdays", DAY.minusDays(10), "REMOVED");

        // Nothing runs on that Wednesday, which the timetable does cover.
        client.get()
                .uri("/api/lines?date=2026-10-21")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"type": "FeatureCollection", "features": []}
                        """, JsonCompareMode.STRICT);
        expectNotFound("2026-10-19", "The timetable covers 2026-10-20 to 2026-10-30");
        expectNotFound("2026-10-31", "The timetable covers 2026-10-20 to 2026-10-30");
    }

    // A day that does not exist, other formats, an offset, a year PostgreSQL cannot store, nothing.
    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "20.10.2026", "2026-10-20Z", "+999999998-01-01", ""})
    void rejectsAnythingButAnExistingDayAsYearMonthDay(String date) {
        client.get()
                .uri(uri -> uri.path("/api/lines").queryParam("date", "{date}").build(date))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .json("""
                        {"title": "Bad Request", "status": 400, "instance": "/api/lines",
                         "detail": "The date must be a day written as YYYY-MM-DD, not '%s'"}
                        """.formatted(date), JsonCompareMode.STRICT);
    }

    private void expectNotFound(String date, String detail) {
        client.get()
                .uri("/api/lines?date=" + date)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .cacheControl(CacheControl.noCache())
                .expectBody()
                .json("""
                        {"title": "Not Found", "status": 404, "instance": "/api/lines", "detail": "%s"}
                        """.formatted(detail), JsonCompareMode.STRICT);
    }

    // A service with one route of the same id, whose only trip follows the shape `shape`.
    private void addRunningService(String id, String weekday, LocalDate start, LocalDate end) {
        addWeeklyService(id, weekday, start, end);
        addRoute(id, id, null, "BUS", null, null);
        addTrip(id, id, id, "shape");
    }

    private void addWeeklyService(String id, String weekday, LocalDate start, LocalDate end) {
        jdbc.sql("""
                        INSERT INTO timetable.service_calendars
                        VALUES (:id, false, false, false, false, false, false, false, :start, :end)
                        """).param("id", id).param("start", start).param("end", end).update();
        jdbc.sql("UPDATE timetable.service_calendars SET %s = true WHERE service_id = :id".formatted(weekday))
                .param("id", id)
                .update();
    }

    private void addException(String service, LocalDate date, String type) {
        jdbc.sql("INSERT INTO timetable.calendar_dates VALUES (?, ?, ?)")
                .params(service, date, type)
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

    private void addTrip(String id, String route, String service, @Nullable String shape) {
        jdbc.sql("""
                        INSERT INTO timetable.trips (id, route_id, service_id, shape_id)
                        VALUES (:id, :route, :service, :shape)
                        """)
                .param("id", id)
                .param("route", route)
                .param("service", service)
                .param("shape", shape)
                .update();
    }

    // Inserted from the last point to the first, so the response must order them by sequence.
    private void addShape(String id, double... longitudesAndLatitudes) {
        for (int i = longitudesAndLatitudes.length - 2; i >= 0; i -= 2) {
            jdbc.sql("""
                            INSERT INTO timetable.shape_points (shape_id, sequence, longitude, latitude)
                            VALUES (?, ?, ?, ?)
                            """)
                    .params(id, i, longitudesAndLatitudes[i], longitudesAndLatitudes[i + 1])
                    .update();
        }
    }
}
