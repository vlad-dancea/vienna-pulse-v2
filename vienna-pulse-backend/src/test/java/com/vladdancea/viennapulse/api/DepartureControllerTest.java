package com.vladdancea.viennapulse.api;

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
import org.springframework.http.MediaType;
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
    private static final String PRATERSTERN = "at:49:1040";
    // The lines at Praterstern, as the API serves them.
    private static final String U1 = """
            {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway", "color": "#E3000F",
             "textColor": "#FFFFFF"}""";
    private static final String TRAM_O = """
            {"id": "o", "name": "O", "longName": null, "type": "tram", "color": null, "textColor": null}""";
    private static final String BUS_5B = """
            {"id": "5b", "name": "5B", "longName": null, "type": "bus", "color": null, "textColor": null}""";

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
        // forgets those of earlier tests.
        clock.set("2100-01-01T00:00:00Z");
        live.round();
        monitor.reset();
        // Half past midnight on DAY in Vienna.
        clock.set("2026-10-19T22:30:00Z");
        timetable.clear();
        timetable.addStop(STOP + ":0:1", "Stephansplatz");
        timetable.addStop(STOP + ":0:2", "Stephansplatz");
        timetable.addStop(STOP + ":0:3", "Stephansplatz U1");
        timetable.addStop(NEXT_STOP, "Karlsplatz");
        // Its id starts like the stop's, but it is another stop.
        timetable.addStop("at:49:13200:0:1", "Elsewhere");
        timetable.addRoute("u1", "U1", "Leopoldau - Oberlaa", RouteType.SUBWAY, "E3000F", "FFFFFF");
        timetable.addRoute("n25", "N25", null, RouteType.BUS, null, null);
    }

    @Test
    void servesTheDeparturesOfEveryPlatformInTheNextHour() {
        timetable.addWeeklyService("mondays", DAY.minusDays(1));
        timetable.addWeeklyService("tuesdays", DAY);
        timetable.addWeeklyService("removed", DAY);
        timetable.addException("removed", DAY, ExceptionType.REMOVED);
        timetable.addException("added", DAY, ExceptionType.ADDED);

        // Yesterday's trips run past midnight; the first has left already.
        addDeparture("left", "u1", "mondays", STOP + ":0:1", "24:20:00");
        addDeparture("late", "u1", "mondays", STOP + ":0:1", "24:40:00");
        addDeparture("now", "u1", "tuesdays", STOP + ":0:2", "00:30:00");
        addDeparture("night", "n25", "tuesdays", STOP + ":0:3", "01:00:00");
        addDeparture("removed", "u1", "removed", STOP + ":0:1", "01:05:00");
        addDeparture("added", "u1", "added", STOP + ":0:1", "01:10:00");
        addDeparture("elsewhere", "u1", "tuesdays", "at:49:13200:0:1", "01:12:00");
        timetable.addTrip(
                "no-pickup",
                "u1",
                "tuesdays",
                null,
                Call.at(1, STOP + ":0:1", "01:14:00").withPickup(PickupDropOffType.NONE),
                Call.at(2, NEXT_STOP, "01:20:00"));
        // Ends at the stop, so nobody boards there although the feed allows it.
        timetable.addTrip(
                "arrives",
                "u1",
                "tuesdays",
                null,
                Call.at(1, NEXT_STOP, "01:15:00"),
                Call.at(2, STOP + ":0:1", "01:16:00"));
        addDeparture("in-an-hour", "u1", "tuesdays", STOP + ":0:1", "01:30:00");

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
                        {"stop": {"id": "at:49:1320", "name": "Stephansplatz"}, "liveStatus": "pending",
                         "departures": [
                           {"plannedTime": "2026-10-20T00:30:00+02:00", "liveTime": null, "headsign": "now",
                            "platform": "at:49:1320:0:2",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}},
                           {"plannedTime": "2026-10-20T00:40:00+02:00", "liveTime": null, "headsign": "late",
                            "platform": "at:49:1320:0:1",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}},
                           {"plannedTime": "2026-10-20T01:00:00+02:00", "liveTime": null, "headsign": "night",
                            "platform": "at:49:1320:0:3",
                            "line": {"id": "n25", "name": "N25", "longName": null, "type": "bus",
                                     "color": null, "textColor": null}},
                           {"plannedTime": "2026-10-20T01:10:00+02:00", "liveTime": null, "headsign": "added",
                            "platform": "at:49:1320:0:1",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}}
                         ]}
                        """, JsonCompareMode.STRICT);
    }

    @Test
    void servesTomorrowsFirstDeparturesBeforeMidnight() {
        // 23:40 on DAY in Vienna.
        clock.set("2026-10-20T21:40:00Z");
        timetable.addWeeklyService("tuesdays", DAY);
        timetable.addWeeklyService("wednesdays", DAY.plusDays(1));
        addDeparture("after-midnight", "u1", "tuesdays", STOP + ":0:1", "24:10:00");
        addDeparture("tomorrow", "u1", "wednesdays", STOP + ":0:1", "00:20:00");
        addDeparture("in-an-hour", "u1", "wednesdays", STOP + ":0:1", "00:40:00");

        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.departures[*].plannedTime")
                .isEqualTo(List.of("2026-10-21T00:10:00+02:00", "2026-10-21T00:20:00+02:00"));
    }

    // The clock has fractions of a second, and the timetable whole seconds.
    @Test
    void servesADepartureInTheLastFractionOfASecondOfTheHour() {
        // 00:30:00.4 on DAY in Vienna.
        clock.set("2026-10-19T22:30:00.400Z");
        timetable.addWeeklyService("tuesdays", DAY);
        addDeparture("in-an-hour", "u1", "tuesdays", STOP + ":0:1", "01:30:00");
        addDeparture("a-second-later", "u1", "tuesdays", STOP + ":0:1", "01:30:01");

        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.departures[*].plannedTime")
                .isEqualTo(List.of("2026-10-20T01:30:00+02:00"));
    }

    @Test
    void countsTheTimesFromNoonMinusTwelveHoursWhenTheClocksGoBack() {
        // 07:30 on Sunday 2026-10-25, after the clocks went back at 03:00 summer time.
        clock.set("2026-10-25T06:30:00Z");
        timetable.addWeeklyService("sundays", LocalDate.parse("2026-10-25"));
        // 08:00 in winter time, nine hours after midnight in summer time. Counted from that
        // midnight, it would be 07:00 and have left.
        addDeparture("morning", "u1", "sundays", STOP + ":0:1", "08:00:00");

        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.departures[*].plannedTime")
                .isEqualTo(List.of("2026-10-25T08:00:00+01:00"));
    }

    @Test
    void servesTheNextTwoDeparturesOfEachLineAndHeadsign() {
        timetable.addWeeklyService("tuesdays", DAY);
        addDepartureTowards("u1-1", "Leopoldau", "u1", "00:31:00");
        addDepartureTowards("u1-2", "Oberlaa", "u1", "00:32:00");
        addDepartureTowards("u1-3", "Leopoldau", "u1", "00:33:00");
        addDepartureTowards("u1-4", "Leopoldau", "u1", "00:35:00");
        addDepartureTowards("n25-1", "Leopoldau", "n25", "00:34:00");
        addDepartureTowards("n25-2", "Leopoldau", "n25", "00:36:00");
        addDepartureTowards("n25-3", "Leopoldau", "n25", "00:38:00");

        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.departures[*].plannedTime")
                .isEqualTo(List.of(
                        "2026-10-20T00:31:00+02:00",
                        "2026-10-20T00:32:00+02:00",
                        "2026-10-20T00:33:00+02:00",
                        "2026-10-20T00:34:00+02:00",
                        "2026-10-20T00:36:00+02:00"));
    }

    // A busy stop has more lines and headsigns than a fixed number of departures would cover.
    @Test
    void servesEveryLineAndHeadsignOfABusyStop() {
        timetable.addWeeklyService("tuesdays", DAY);
        for (int headsign = 0; headsign < 12; headsign++) {
            addDepartureTowards(
                    "first-" + headsign, "towards-" + headsign, "u1", "00:%02d:00".formatted(31 + headsign));
            addDepartureTowards(
                    "second-" + headsign, "towards-" + headsign, "u1", "00:%02d:00".formatted(51 + headsign));
        }

        client.get()
                .uri("/api/stops/at:49:1320/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.departures.length()")
                .isEqualTo(24);
    }

    // The fake monitor's answer came from Praterstern at 21:47:55 on Saturday 2026-10-24.
    @Test
    void addsTheLiveTimesOnceARoundFetchedTheStation() {
        clock.set(MonitorServer.ANSWERED_AT);
        timetable.addWeeklyService("saturdays", LocalDate.parse("2026-10-24"));
        timetable.addStop(PRATERSTERN + ":0:1", "Praterstern");
        timetable.addStop(PRATERSTERN + ":0:2", "Praterstern");
        timetable.addStop(PRATERSTERN + ":0:3", "Praterstern");
        timetable.addRoute("o", "O", null, RouteType.TRAM, null, null);
        timetable.addRoute("5b", "5B", null, RouteType.BUS, null, null);
        // Without a live time, the first left before now, and the second is late.
        addSaturdayDeparture("o-left", "Wien Bruno-Marek-Allee", 0, "o", ":0:2", "21:26:00");
        addSaturdayDeparture("o-late", "Wien Bruno-Marek-Allee", 0, "o", ":0:2", "21:41:00");
        addSaturdayDeparture("o-next", "Wien Bruno-Marek-Allee", 0, "o", ":0:2", "21:56:00");
        addSaturdayDeparture("o-later", "Wien Bruno-Marek-Allee", 0, "o", ":0:2", "22:10:00");
        // Wiener Linien call the direction towards Heiligenstadt H, which the timetable calls 1.
        addSaturdayDeparture("5b", "Wien Heiligenstadt", 1, "5b", ":0:3", "21:49:00");
        addSaturdayDeparture("u1-oberlaa", "Wien Oberlaa", 1, "u1", ":0:1", "21:49:00");
        addSaturdayDeparture("u1-alaudagasse", "Wien Alaudagasse", 1, "u1", ":0:1", "21:57:00");
        // Leaves when the U1 to Oberlaa does, the other way, which the answer has no departures of.
        addSaturdayDeparture("u1-leopoldau", "Wien Leopoldau", 0, "u1", ":0:1", "21:49:00");

        client.get()
                .uri("/api/stops/at:49:1040/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json(
                        """
                        {"stop": {"id": "at:49:1040", "name": "Praterstern"}, "liveStatus": "pending", "departures": [
                          %s, %s, %s, %s, %s, %s
                        ]}
                        """.formatted(
                                        praterstern("21:49:00", null, "Wien Heiligenstadt", ":0:3", BUS_5B),
                                        praterstern("21:49:00", null, "Wien Leopoldau", ":0:1", U1),
                                        praterstern("21:49:00", null, "Wien Oberlaa", ":0:1", U1),
                                        praterstern("21:56:00", null, "Wien Bruno-Marek-Allee", ":0:2", TRAM_O),
                                        praterstern("21:57:00", null, "Wien Alaudagasse", ":0:1", U1),
                                        praterstern("22:10:00", null, "Wien Bruno-Marek-Allee", ":0:2", TRAM_O)),
                        JsonCompareMode.STRICT);

        live.round();

        client.get()
                .uri("/api/stops/at:49:1040/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json(
                        """
                        {"stop": {"id": "at:49:1040", "name": "Praterstern"}, "liveStatus": "live", "departures": [
                          %s, %s, %s, %s, %s, %s
                        ]}
                        """.formatted(
                                        praterstern("21:49:00", "21:49:00", "Wien Heiligenstadt", ":0:3", BUS_5B),
                                        praterstern("21:49:00", null, "Wien Leopoldau", ":0:1", U1),
                                        praterstern("21:41:00", "21:49:07", "Wien Bruno-Marek-Allee", ":0:2", TRAM_O),
                                        praterstern("21:49:00", "21:49:34", "Wien Oberlaa", ":0:1", U1),
                                        praterstern("21:56:00", "21:57:40", "Wien Bruno-Marek-Allee", ":0:2", TRAM_O),
                                        praterstern("21:57:00", "21:58:25", "Wien Alaudagasse", ":0:1", U1)),
                        JsonCompareMode.STRICT);
    }

    @Test
    void hasNoLiveTimesForAStopOutsideVienna() {
        timetable.addStop("at:43:3121:0:1", "Baden Josefsplatz");

        client.get()
                .uri("/api/stops/at:43:3121/departures")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"stop": {"id": "at:43:3121", "name": "Baden Josefsplatz"}, "liveStatus": "unavailable",
                         "departures": []}
                        """, JsonCompareMode.STRICT);
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
                        {"stop": {"id": "at:49:1320", "name": "Stephansplatz"}, "liveStatus": "pending",
                         "departures": []}
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

    // A trip on Saturday 2026-10-24 towards `headsign` in `direction`, leaving Praterstern's platform
    // with the id ending in `platform` at `time` and calling at the next stop after.
    private void addSaturdayDeparture(
            String trip, String headsign, int direction, String route, String platform, String time) {
        timetable.addTripTowards(
                trip,
                headsign,
                direction,
                route,
                "saturdays",
                Call.at(1, PRATERSTERN + platform, time),
                Call.at(2, NEXT_STOP, time));
    }

    // A departure from Praterstern on 2026-10-24 as the API answers it, with its times in Vienna.
    private static String praterstern(
            String plannedTime, @Nullable String liveTime, String headsign, String platform, String line) {
        return """
                {"plannedTime": "2026-10-24T%s+02:00", "liveTime": %s, "headsign": "%s", "platform": "%s",
                 "line": %s}""".formatted(
                        plannedTime,
                        liveTime == null ? "null" : "\"2026-10-24T%s+02:00\"".formatted(liveTime),
                        headsign,
                        PRATERSTERN + platform,
                        line);
    }

    // A trip with its headsign named after it, leaving `platform` and calling at the next stop after.
    private void addDeparture(String trip, String route, String service, String platform, String time) {
        timetable.addTrip(trip, route, service, null, Call.at(1, platform, time), Call.at(2, NEXT_STOP, time));
    }

    // A trip towards `headsign` that leaves the stop today and calls at the next stop after.
    private void addDepartureTowards(String trip, String headsign, String route, String time) {
        timetable.addTripTowards(
                trip, headsign, route, "tuesdays", Call.at(1, STOP + ":0:1", time), Call.at(2, NEXT_STOP, time));
    }
}
