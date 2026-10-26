package com.vladdancea.viennapulse.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class MatchingTest {
    // Bus 5B goes towards Heiligenstadt in the timetable's direction 1, which Wiener Linien call H.
    // Without headsigns, the reading that pairs both departures beats the usual one, which pairs one.
    @Test
    void readsTheDirectionsTheWayThatPairsMoreWhereNoHeadsignNamesTheDestination() {
        var first = planned("5b-1", "5B", null, 1, "21:49:00");
        var second = planned("5b-2", "5B", null, 1, "22:04:00");
        var otherWay = planned("5b-praterstern", "5B", null, 0, "21:50:00");

        var paired = Matching.planned(
                List.of(first, otherWay, second),
                List.of(
                        live("5B", "H", "Bhf. Heiligenstadt S U", "21:49:00"),
                        live("5B", "H", "Bhf. Heiligenstadt S U", "22:04:00")));

        assertThat(paired).containsExactly(first, second);
    }

    // Tram 10 at Hietzing, where Wiener Linien plan the trams towards Unter St. Veit five minutes after
    // the timetable. A destination that no headsign names, as on a diversion, goes the way the line's
    // named departures do, though the other reading would pair it with a tram towards Dornbach.
    @Test
    void readsADepartureWhoseDestinationNoHeadsignNamesTheWayItsLineDoes() {
        var dornbach = planned("dornbach-1", "10", "Wien Dornbach", 1, "12:45:00");
        var timetable = List.of(
                dornbach,
                planned("unter-st-veit", "10", "Wien Unter St. Veit, Hummelgasse", 0, "12:48:00"),
                planned("dornbach-2", "10", "Wien Dornbach", 1, "12:55:00"));

        var paired = Matching.planned(
                timetable,
                List.of(
                        live("10", "H", "Unter St. Veit, Hummelgasse", "12:43:00"),
                        live("10", "R", "Dornbach", "12:46:00"),
                        live("10", "H", "Schloss Schönbrunn", "12:53:00")));

        assertThat(paired).containsExactly(null, dornbach, null);
    }

    // Tram 2 at Schwedenplatz, turning short at Lorenz-Bayer-Platz on the way to Dornbach. Its
    // destination shares only the word Platz with Friedrich-Engels-Platz, the other way, which decides
    // nothing, so its R does.
    @Test
    void readsADestinationThatSharesOnlyACommonWordWithAHeadsignByItsDirection() {
        var dornbach = planned("dornbach", "2", "Wien Dornbach", 1, "12:09:00");
        var timetable =
                List.of(planned("friedrich-engels-platz", "2", "Wien Friedrich-Engels-Platz", 0, "12:06:00"), dornbach);

        var paired = Matching.planned(timetable, List.of(live("2", "R", "Lorenz-Bayer-Platz", "12:08:00")));

        assertThat(paired).containsExactly(dornbach);
    }

    private static PlannedDeparture planned(
            String trip, String line, @Nullable String headsign, int direction, String time) {
        return new PlannedDeparture(trip, line.toLowerCase(Locale.ROOT), line, headsign, direction, vienna(time));
    }

    private static LiveDeparture live(String line, String direction, String towards, String planned) {
        return new LiveDeparture(1, line, direction, towards, vienna(planned), null, 0);
    }

    private static Instant vienna(String time) {
        return Instant.parse("2026-10-26T" + time + "+01:00");
    }
}
