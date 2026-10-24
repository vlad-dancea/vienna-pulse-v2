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

    @Autowired
    RestTestClient client;

    @Autowired
    TimetableFixture timetable;

    @Autowired
    TestClock clock;

    @BeforeEach
    void removeTheTimetable() {
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
                        {"stop": {"id": "at:49:1320", "name": "Stephansplatz"},
                         "departures": [
                           {"time": "2026-10-20T00:30:00+02:00", "headsign": "now", "platform": "at:49:1320:0:2",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}},
                           {"time": "2026-10-20T00:40:00+02:00", "headsign": "late", "platform": "at:49:1320:0:1",
                            "line": {"id": "u1", "name": "U1", "longName": "Leopoldau - Oberlaa", "type": "subway",
                                     "color": "#E3000F", "textColor": "#FFFFFF"}},
                           {"time": "2026-10-20T01:00:00+02:00", "headsign": "night", "platform": "at:49:1320:0:3",
                            "line": {"id": "n25", "name": "N25", "longName": null, "type": "bus",
                                     "color": null, "textColor": null}},
                           {"time": "2026-10-20T01:10:00+02:00", "headsign": "added", "platform": "at:49:1320:0:1",
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
                .jsonPath("$.departures[*].time")
                .isEqualTo(List.of("2026-10-21T00:10:00+02:00", "2026-10-21T00:20:00+02:00"));
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
                .jsonPath("$.departures[*].time")
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
                .jsonPath("$.departures[*].time")
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
        timetable.addTrip(trip, route, service, null, Call.at(1, platform, time), Call.at(2, NEXT_STOP, time));
    }

    // A trip towards `headsign` that leaves the stop today and calls at the next stop after.
    private void addDepartureTowards(String trip, String headsign, String route, String time) {
        timetable.addTripTowards(
                trip, headsign, route, "tuesdays", Call.at(1, STOP + ":0:1", time), Call.at(2, NEXT_STOP, time));
    }
}
