package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class DepartureControllerTest {
    // A Tuesday.
    private static final LocalDate DAY = LocalDate.parse("2026-10-20");
    private static final String STOP = "at:49:1320";
    private static final String NEXT_STOP = "at:49:1350:0:1";

    // Each test sets the time, which the application reads through the clock bean.
    private static Clock now = Clock.systemUTC();

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcClient jdbc;

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
        for (var table : List.of("stops", "routes", "trips", "stop_times", "service_calendars", "calendar_dates")) {
            jdbc.sql("DELETE FROM timetable." + table).update();
        }
        addStop(STOP + ":0:1", "Stephansplatz");
        addStop(STOP + ":0:2", "Stephansplatz");
        addStop(STOP + ":0:3", "Stephansplatz U1");
        addStop(NEXT_STOP, "Karlsplatz");
        // Its id starts like the stop's, but it is another stop.
        addStop("at:49:13200:0:1", "Elsewhere");
        addRoute("u1", "U1", "Leopoldau - Oberlaa", "SUBWAY", "E3000F", "FFFFFF");
        addRoute("n25", "N25", null, "BUS", null, null);
    }

    @Test
    void servesTheNextDeparturesOfEveryPlatformAcrossMidnight() {
        // Half past midnight on DAY in Vienna.
        now = Clock.fixed(Instant.parse("2026-10-19T22:30:00Z"), ZoneOffset.UTC);
        addWeeklyService("mondays", "monday", DAY.minusDays(1));
        addWeeklyService("tuesdays", "tuesday", DAY);
        addWeeklyService("wednesdays", "wednesday", DAY.plusDays(1));
        addWeeklyService("removed", "tuesday", DAY);
        addException("removed", DAY, "REMOVED");
        addException("added", DAY, "ADDED");

        // Yesterday's trips run past midnight; the first has left already.
        addDeparture("left", "u1", "mondays", STOP + ":0:1", "24:20:00");
        addDeparture("late", "u1", "mondays", STOP + ":0:1", "24:40:00");
        addDeparture("now", "u1", "tuesdays", STOP + ":0:2", "00:30:00");
        addDeparture("morning", "n25", "tuesdays", STOP + ":0:3", "05:00:00");
        addDeparture("removed", "u1", "removed", STOP + ":0:1", "05:10:00");
        addDeparture("added", "u1", "added", STOP + ":0:1", "05:20:00");
        addDeparture("elsewhere", "u1", "tuesdays", "at:49:13200:0:1", "05:30:00");
        addTrip("no-pickup", "u1", "tuesdays");
        addCall("no-pickup", 1, STOP + ":0:1", "05:40:00", "NONE");
        addCall("no-pickup", 2, NEXT_STOP, "05:45:00", "REGULAR");
        // Ends at the stop, so nobody boards there although the feed allows it.
        addTrip("arrives", "u1", "tuesdays");
        addCall("arrives", 1, NEXT_STOP, "05:50:00", "REGULAR");
        addCall("arrives", 2, STOP + ":0:1", "05:55:00", "REGULAR");
        addDeparture("tomorrow", "u1", "wednesdays", STOP + ":0:1", "04:30:00");
        // Leaves after tomorrow's service day, where the day after's earliest trips would be missing.
        addDeparture("too-late", "u1", "wednesdays", STOP + ":0:1", "24:30:00");

        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectHeader()
                .cacheControl(CacheControl.noCache())
                .expectBody()
                .json("""
                        {"stop": {"id": "at:49:1320", "name": "Stephansplatz"},
                         "departures": [
                           {"time": "2026-10-20T00:30:00+02:00", "headsign": "now", "platform": "at:49:1320:0:2",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}},
                           {"time": "2026-10-20T00:40:00+02:00", "headsign": "late", "platform": "at:49:1320:0:1",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}},
                           {"time": "2026-10-20T05:00:00+02:00", "headsign": "morning", "platform": "at:49:1320:0:3",
                            "line": {"id": "n25", "name": "N25", "longName": null, "type": "bus",
                                     "color": null, "textColor": null}},
                           {"time": "2026-10-20T05:20:00+02:00", "headsign": "added", "platform": "at:49:1320:0:1",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}},
                           {"time": "2026-10-21T04:30:00+02:00", "headsign": "tomorrow", "platform": "at:49:1320:0:1",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}}
                         ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void countsTheTimesFromNoonMinusTwelveHoursWhenTheClocksGoBack() {
        // Sunday 2026-10-25, before the clocks go back at 03:00 summer time.
        now = Clock.fixed(Instant.parse("2026-10-24T23:30:00Z"), ZoneOffset.UTC);
        addWeeklyService("sundays", "sunday", LocalDate.parse("2026-10-25"));
        // 08:00 in winter time, nine hours after midnight in summer time.
        addDeparture("morning", "u1", "sundays", STOP + ":0:1", "08:00:00");

        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.departures[*].time")
                .isEqualTo(List.of("2026-10-25T08:00:00+01:00"));
    }

    @Test
    void servesTheFirstTwentyDepartures() {
        now = Clock.fixed(Instant.parse("2026-10-19T22:30:00Z"), ZoneOffset.UTC);
        addWeeklyService("tuesdays", "tuesday", DAY);
        for (int minute = 20; minute >= 0; minute--) {
            addDeparture("trip-" + minute, "u1", "tuesdays", STOP + ":0:1", "06:%02d:00".formatted(minute));
        }

        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.departures[*].headsign")
                .isEqualTo(IntStream.range(0, 20)
                        .mapToObj(minute -> "trip-" + minute)
                        .toList());
    }

    @Test
    void answersAStopWithNothingLeavingWithNoDepartures() {
        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"stop": {"id": "at:49:1320", "name": "Stephansplatz"}, "departures": []}
                        """, JsonCompareMode.STRICT);
    }

    // A platform's own id is not a stop's.
    @Test
    void answersNotFoundForAnUnknownStop() {
        client.get()
                .uri("/api/stops/at:49:1320:0:1/departures")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .cacheControl(CacheControl.noCache())
                .expectBody()
                .json("""
                        {"title": "Not Found", "status": 404, "instance": "/api/stops/at:49:1320:0:1/departures",
                         "detail": "There is no stop 'at:49:1320:0:1'"}
                        """, JsonCompareMode.STRICT);
    }

    // A trip with its headsign named after it, leaving `platform` and calling at the next stop after.
    private void addDeparture(String trip, String route, String service, String platform, String time) {
        addTrip(trip, route, service);
        addCall(trip, 1, platform, time, "REGULAR");
        addCall(trip, 2, NEXT_STOP, time, "REGULAR");
    }

    private void addStop(String id, String name) {
        jdbc.sql("INSERT INTO timetable.stops (id, name, latitude, longitude) VALUES (?, ?, 48.2, 16.37)")
                .params(id, name)
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

    private void addTrip(String id, String route, String service) {
        jdbc.sql("INSERT INTO timetable.trips (id, route_id, service_id, headsign) VALUES (?, ?, ?, ?)")
                .params(id, route, service, id)
                .update();
    }

    // Times written as in the feed, where hours can pass 24.
    private void addCall(String trip, int sequence, String stop, String time, String pickup) {
        var parts = time.split(":", -1);
        var seconds = Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
        jdbc.sql("""
                        INSERT INTO timetable.stop_times
                            (trip_id, sequence, stop_id, arrival_time, departure_time, pickup_type, drop_off_type)
                        VALUES (?, ?, ?, ?, ?, ?, 'REGULAR')
                        """).params(trip, sequence, stop, seconds, seconds, pickup).update();
    }
}
