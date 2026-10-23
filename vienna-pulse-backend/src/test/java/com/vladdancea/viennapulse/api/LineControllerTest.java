package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.TestClock;
import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.TimetableFixture;
import com.vladdancea.viennapulse.importer.read.calendar.ExceptionType;
import com.vladdancea.viennapulse.importer.read.route.RouteType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
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
    TimetableFixture timetable;

    @Autowired
    TestClock clock;

    @BeforeEach
    void removeTheTimetable() {
        // Half past midnight on DAY in Vienna, still the day before in UTC.
        clock.set("2026-10-19T22:30:00Z");
        timetable.clear();
    }

    @Test
    void servesEachRunningRouteWithTheDistinctPathsOfItsShapes() {
        timetable.addWeeklyService("weekdays", DayOfWeek.TUESDAY, DAY, DAY);
        timetable.addWeeklyService("sundays", DayOfWeek.SUNDAY, DAY, DAY);
        timetable.addRoute("tram-62", "62", "Oper - Lainz", RouteType.TRAM, "C00808", "FFFFFF");
        timetable.addRoute("bus-n25", "N25", null, RouteType.BUS, null, null);
        timetable.addRoute("subway-u1", "U1", "Leopoldau - Oberlaa", RouteType.SUBWAY, "E3000F", "FFFFFF");

        // Two shapes with the same points, rounded to six decimals, make one path.
        timetable.addShape("62-b", 16.1234561, 48.1, 16.2, 48.2);
        timetable.addShape("62-a", 16.1234559, 48.1, 16.2, 48.2);
        timetable.addShape("62-c", 16.3, 48.3, 16.4, 48.4);
        timetable.addTrip("t1", "tram-62", "weekdays", "62-c");
        timetable.addTrip("t2", "tram-62", "weekdays", "62-b");
        timetable.addTrip("t3", "tram-62", "weekdays", "62-a");
        timetable.addTrip("t4", "tram-62", "weekdays", "62-a");
        // A shape of a single point is no line.
        timetable.addShape("n25-a", 16.5, 48.5);
        timetable.addShape("n25-b", 16.5, 48.5, 16.6, 48.6);
        timetable.addTrip("t5", "bus-n25", "weekdays", "n25-a");
        timetable.addTrip("t6", "bus-n25", "weekdays", "n25-b");
        // Runs on Sundays only.
        timetable.addShape("u1", 16.7, 48.7, 16.8, 48.8);
        timetable.addTrip("t7", "subway-u1", "sundays", "u1");
        // Runs, but along no shape of two points or more, so there is nothing to draw.
        timetable.addRoute("bus-13a", "13A", null, RouteType.BUS, null, null);
        timetable.addTrip("t8", "bus-13a", "weekdays", null);
        timetable.addTrip("t9", "bus-13a", "weekdays", "n25-a");

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
        timetable.addWeeklyService("weekdays", DayOfWeek.TUESDAY, DAY, DAY);
        timetable.addRoute("bus-13a", "13A", null, RouteType.BUS, null, null);
        timetable.addShape("single", 16.5, 48.5);
        timetable.addTrip("trip", "bus-13a", "weekdays", shape);

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
        timetable.addShape("shape", 16.1, 48.1, 16.2, 48.2);
        addRunningService("tuesdays", DayOfWeek.TUESDAY, DAY, DAY);
        addRunningService("wednesdays", DayOfWeek.WEDNESDAY, DAY.minusDays(7), DAY.plusDays(7));
        addRunningService("ended", DayOfWeek.TUESDAY, DAY.minusDays(7), DAY.minusDays(1));
        addRunningService("removed", DayOfWeek.TUESDAY, DAY.minusDays(7), DAY.plusDays(7));
        timetable.addException("removed", DAY, ExceptionType.REMOVED);
        addRunningService("added", DayOfWeek.WEDNESDAY, DAY.minusDays(7), DAY.plusDays(7));
        timetable.addException("added", DAY, ExceptionType.ADDED);
        timetable.addRoute("only-added", "only-added", null, RouteType.BUS, null, null);
        timetable.addTrip("only-added", "only-added", "only-added", "shape");
        timetable.addException("only-added", DAY, ExceptionType.ADDED);

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
        timetable.addShape("shape", 16.1, 48.1, 16.2, 48.2);
        timetable.addRoute("night", "N6", null, RouteType.BUS, null, null);
        timetable.addTrip("night", "night", "today", "shape");
        timetable.addException("today", DAY, ExceptionType.ADDED);

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

        timetable.addWeeklyService("weekdays", DayOfWeek.TUESDAY, DAY, DAY.plusDays(4));
        // A day added after the last week counts, a day removed before the first does not.
        timetable.addException("weekdays", DAY.plusDays(10), ExceptionType.ADDED);
        timetable.addException("weekdays", DAY.minusDays(10), ExceptionType.REMOVED);

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
    private void addRunningService(String id, DayOfWeek weekday, LocalDate start, LocalDate end) {
        timetable.addWeeklyService(id, weekday, start, end);
        timetable.addRoute(id, id, null, RouteType.BUS, null, null);
        timetable.addTrip(id, id, id, "shape");
    }
}
