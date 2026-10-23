package com.vladdancea.viennapulse.importer.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.calendar.CalendarDate;
import com.vladdancea.viennapulse.importer.read.calendar.ExceptionType;
import com.vladdancea.viennapulse.importer.read.calendar.ServiceCalendar;
import com.vladdancea.viennapulse.importer.read.route.Route;
import com.vladdancea.viennapulse.importer.read.route.RouteType;
import com.vladdancea.viennapulse.importer.read.shape.ShapePoint;
import com.vladdancea.viennapulse.importer.read.stop.Stop;
import com.vladdancea.viennapulse.importer.read.stoptime.PickupDropOffType;
import com.vladdancea.viennapulse.importer.read.stoptime.StopTime;
import com.vladdancea.viennapulse.importer.read.trip.Trip;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// The store commits its own transactions, which a test transaction would swallow.
@JdbcTest
@Import({TestcontainersConfiguration.class, Store.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoreTest {
    private static final List<Route> ROUTES_BEFORE =
            List.of(new Route("U1", "wl", "U1", "Leopoldau – Oberlaa", RouteType.SUBWAY, "E3000F", "FFFFFF"));
    private static final List<Stop> STOPS_BEFORE = List.of(new Stop("s1", "Karlsplatz", 48.20024, 16.36951, "100"));
    private static final List<ShapePoint> SHAPE_POINTS_BEFORE = List.of(
            new ShapePoint("U1.1.H", 48.17417, 16.41447, 1, 0.0),
            new ShapePoint("U1.1.H", 48.17530, 16.41325, 2, 152.75));
    private static final List<Trip> TRIPS_BEFORE = List.of(new Trip("1.T0.U1.1.H", "U1", "T0", "U1.1.H", "Oberlaa", 0));
    private static final List<ServiceCalendar> CALENDARS_BEFORE = List.of(new ServiceCalendar(
            "T0", true, true, true, true, true, false, false, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 12)));
    private static final List<CalendarDate> DATES_BEFORE =
            List.of(new CalendarDate("T0", LocalDate.of(2026, 4, 6), ExceptionType.REMOVED));

    // Commas, quotes and nulls must survive the trip through COPY.
    private static final List<Route> ROUTES_AFTER = List.of(
            new Route("D", null, "D", "Nußdorf, \"Beethovengang\"", RouteType.TRAM, null, null),
            new Route("U2", "wl", "U2", null, RouteType.SUBWAY, "A762A3", "FFFFFF"));
    private static final List<Stop> STOPS_AFTER = List.of(new Stop("s2", "Stephansplatz", 48.20845, 16.37212, null));
    private static final List<ShapePoint> SHAPE_POINTS_AFTER = List.of(
            new ShapePoint("D.1.H", 48.25638, 16.36195, 1, null),
            new ShapePoint("D.1.H", 48.25551, 16.36277, 2, null),
            new ShapePoint("U2.1.H", 48.21950, 16.39278, 1, 0.0));
    private static final List<Trip> TRIPS_AFTER = List.of(
            new Trip("1.T0.D.1.H", "D", "T0", null, null, null),
            new Trip("2.T2.U2.1.H", "U2", "T2", "U2.1.H", "Seestadt", 1));
    private static final List<ServiceCalendar> CALENDARS_AFTER = List.of(new ServiceCalendar(
            "T2",
            false,
            false,
            false,
            false,
            false,
            true,
            true,
            LocalDate.of(2026, 10, 24),
            LocalDate.of(2026, 12, 6)));
    private static final List<CalendarDate> DATES_AFTER = List.of(
            new CalendarDate("T0", LocalDate.of(2026, 10, 26), ExceptionType.ADDED),
            new CalendarDate("T2", LocalDate.of(2026, 11, 1), ExceptionType.REMOVED));
    private static final List<StopTime> STOP_TIMES_AFTER = List.of(
            stopTime("1.T0.D.1.H", 1, "s2", PickupDropOffType.REGULAR, PickupDropOffType.NONE),
            new StopTime(
                    "2.T2.U2.1.H",
                    2,
                    "s2",
                    null,
                    null,
                    PickupDropOffType.PHONE_AGENCY,
                    PickupDropOffType.COORDINATE_WITH_DRIVER,
                    null),
            new StopTime(
                    "2.T2.U2.1.H",
                    3,
                    "s2",
                    90_000,
                    90_060,
                    PickupDropOffType.REGULAR,
                    PickupDropOffType.REGULAR,
                    1847.57));

    @Autowired
    Store store;

    @Autowired
    JdbcClient jdbc;

    @Test
    void replacesTheWholeTimetable() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);
        load(
                ROUTES_AFTER,
                STOPS_AFTER,
                SHAPE_POINTS_AFTER,
                TRIPS_AFTER,
                CALENDARS_AFTER,
                DATES_AFTER,
                STOP_TIMES_AFTER);

        assertThat(routes()).isEqualTo(ROUTES_AFTER);
        assertThat(stops()).isEqualTo(STOPS_AFTER);
        assertThat(shapePoints()).isEqualTo(SHAPE_POINTS_AFTER);
        assertThat(trips()).isEqualTo(TRIPS_AFTER);
        assertThat(calendars()).isEqualTo(CALENDARS_AFTER);
        assertThat(dates()).isEqualTo(DATES_AFTER);
        assertThat(stopTimes()).isEqualTo(STOP_TIMES_AFTER);
        assertThat(stopModes()).containsExactly("s2:{SUBWAY,TRAM}");
    }

    // The rules name the tables of the timetable, which each import replaces and then drops.
    @Test
    void appliesTheTimetableRulesToTheCurrentTimetable() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);
        var platform = new Stop("at:49:1320:0:3", "Stephansplatz", 48.20845, 16.37212, null);
        var stops = List.of(STOPS_AFTER.getFirst(), platform);
        load(ROUTES_AFTER, stops, SHAPE_POINTS_AFTER, TRIPS_AFTER, CALENDARS_AFTER, DATES_AFTER);

        // A Saturday of T2, a Monday T0 is added on and a Sunday T2 is removed from.
        assertThat(runningServices(LocalDate.of(2026, 10, 24))).containsExactly("T2");
        assertThat(runningServices(LocalDate.of(2026, 10, 26))).containsExactly("T0");
        assertThat(runningServices(LocalDate.of(2026, 11, 1))).isEmpty();
        assertThat(jdbc.sql("SELECT id || ' ' || merged_id FROM timetable.stops ORDER BY id")
                        .query(String.class)
                        .list())
                .containsExactly("at:49:1320:0:3 at:49:1320", "s2 s2");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'timetable' AND tablename = 'stops'")
                        .query(String.class)
                        .list())
                .contains("CREATE INDEX stops_merged_id_idx ON timetable.stops USING btree (merged_id)");
    }

    @Test
    void keepsThePreviousTimetableWhenTheLoadFails() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);

        assertThatThrownBy(() -> store.replace(staging -> {
                    staging.copy("routes", Route.class, ROUTES_AFTER::forEach);
                    throw new IOException("feed cut off");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("feed cut off");

        assertThat(routes()).isEqualTo(ROUTES_BEFORE);
        assertThat(stops()).isEqualTo(STOPS_BEFORE);
        assertThat(shapePoints()).isEqualTo(SHAPE_POINTS_BEFORE);
        assertThat(trips()).isEqualTo(TRIPS_BEFORE);
        assertThat(calendars()).isEqualTo(CALENDARS_BEFORE);
        assertThat(dates()).isEqualTo(DATES_BEFORE);
        assertThat(stopTimes()).hasSize(1);
        assertThat(schemas()).containsExactly("timetable");
        assertThat(stopModes()).containsExactly("s1:{SUBWAY}");
    }

    // The reader leaves checks across rows to whoever groups the points, which is the primary key.
    @Test
    void rejectsAShapeThatRepeatsASequenceNumber() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);
        var repeated = List.of(
                new ShapePoint("D.1.H", 48.25638, 16.36195, 1, null),
                new ShapePoint("D.1.H", 48.25551, 16.36277, 1, null));

        assertThatThrownBy(() -> load(ROUTES_AFTER, STOPS_AFTER, repeated, TRIPS_AFTER, CALENDARS_AFTER, DATES_AFTER))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("Key (shape_id, sequence)=(D.1.H, 1) already exists.");

        assertThat(shapePoints()).isEqualTo(SHAPE_POINTS_BEFORE);
    }

    // The trip reader streams and cannot see a repeated id, so the primary key has to.
    @Test
    void rejectsATripThatRepeatsAnId() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);
        var repeated = List.of(
                new Trip("1.T0.D.1.H", "D", "T0", null, null, null),
                new Trip("1.T0.D.1.H", "D", "T2", null, null, null));

        assertThatThrownBy(() ->
                        load(ROUTES_AFTER, STOPS_AFTER, SHAPE_POINTS_AFTER, repeated, CALENDARS_AFTER, DATES_AFTER))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("Key (id)=(1.T0.D.1.H) already exists.");

        assertThat(trips()).isEqualTo(TRIPS_BEFORE);
    }

    @Test
    void rejectsATripThatRepeatsAStopSequence() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);
        var trip = TRIPS_AFTER.getFirst().id();
        var repeated = List.of(
                stopTime(trip, 1, "s2", PickupDropOffType.REGULAR, PickupDropOffType.REGULAR),
                stopTime(trip, 1, "s2", PickupDropOffType.REGULAR, PickupDropOffType.REGULAR));

        assertThatThrownBy(() -> load(
                        ROUTES_AFTER,
                        STOPS_AFTER,
                        SHAPE_POINTS_AFTER,
                        TRIPS_AFTER,
                        CALENDARS_AFTER,
                        DATES_AFTER,
                        repeated))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("Key (trip_id, sequence)=(1.T0.D.1.H, 1) already exists.");

        assertThat(stopTimes()).hasSize(1);
    }

    @Test
    void rejectsALoadThatSkipsATable() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);

        assertThatThrownBy(() -> store.replace(staging -> staging.copy("routes", Route.class, ROUTES_AFTER::forEach)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "The import loaded nothing into [calendar_dates, service_calendars, shape_points, stop_modes, stop_times, stops, trip_spans, trips]");

        assertThat(routes()).isEqualTo(ROUTES_BEFORE);
    }

    @Test
    void classifiesDuplicateUnsortedCallsAndKeepsUnusedStops() throws IOException {
        var stops = List.of(STOPS_AFTER.getFirst(), new Stop("unused", "Unused", 48.2, 16.4, null));
        var stopTimes = List.of(
                stopTime(TRIPS_AFTER.getLast().id(), 1, "s2", PickupDropOffType.NONE, PickupDropOffType.NONE),
                stopTime(TRIPS_AFTER.getFirst().id(), 2, "s2", PickupDropOffType.NONE, PickupDropOffType.REGULAR),
                stopTime(TRIPS_AFTER.getFirst().id(), 1, "s2", PickupDropOffType.NONE, PickupDropOffType.REGULAR));
        load(ROUTES_AFTER, stops, SHAPE_POINTS_AFTER, TRIPS_AFTER, CALENDARS_AFTER, DATES_AFTER, stopTimes);
        assertThat(stopModes()).containsExactly("s2:{TRAM}", "unused:{}");
        assertThat(store.needsImport()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"missing,s1,REGULAR", "missing,s1,NONE", "1.T0.U1.1.H,missing,REGULAR", "1.T0.U1.1.H,missing,NONE"})
    void rejectsUnknownReferencesEvenOnExcludedCalls(String trip, String stop, PickupDropOffType type)
            throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);
        assertThatThrownBy(() -> load(
                        ROUTES_BEFORE,
                        STOPS_BEFORE,
                        SHAPE_POINTS_BEFORE,
                        TRIPS_BEFORE,
                        CALENDARS_BEFORE,
                        DATES_BEFORE,
                        List.of(stopTime(trip, 1, stop, type, type))))
                .isInstanceOf(FeedFormatException.class)
                .hasMessageContaining("unknown trip, stop or route");
        assertThat(stopModes()).containsExactly("s1:{SUBWAY}");
        assertThat(stops()).isEqualTo(STOPS_BEFORE);
    }

    @Test
    void rejectsACallWhoseTripHasAnUnknownRoute() throws IOException {
        var trips = List.of(new Trip("t", "missing", "T0", null, null, null));
        assertThatThrownBy(() -> load(
                        ROUTES_BEFORE,
                        STOPS_BEFORE,
                        SHAPE_POINTS_BEFORE,
                        trips,
                        CALENDARS_BEFORE,
                        DATES_BEFORE,
                        List.of(stopTime("t", 1, "s1", PickupDropOffType.NONE, PickupDropOffType.NONE))))
                .isInstanceOf(FeedFormatException.class)
                .hasMessageContaining("unknown trip, stop or route");
    }

    @Test
    void detectsAnUpgradeUntilClassificationHasCompletedEvenWithoutPassengerCalls() throws IOException {
        var withoutPassengers = List.of(
                stopTime(TRIPS_BEFORE.getFirst().id(), 1, "s1", PickupDropOffType.NONE, PickupDropOffType.NONE));
        load(
                ROUTES_BEFORE,
                STOPS_BEFORE,
                SHAPE_POINTS_BEFORE,
                TRIPS_BEFORE,
                CALENDARS_BEFORE,
                DATES_BEFORE,
                withoutPassengers);
        assertThat(store.needsImport()).isFalse();
        assertThat(stopModes()).containsExactly("s1:{}");
        jdbc.sql("DELETE FROM timetable.stop_modes").update();
        assertThat(store.needsImport()).isTrue();
    }

    @Test
    void recordsWhenEachTripLeavesItsFirstStopAndReachesItsLast() throws IOException {
        var first = TRIPS_AFTER.getFirst().id();
        var last = TRIPS_AFTER.getLast().id();
        var stopTimes = List.of(
                new StopTime(last, 3, "s2", 90_000, 90_060, PickupDropOffType.REGULAR, PickupDropOffType.REGULAR, 0.0),
                // A call between two timed ones may leave its times out.
                new StopTime(last, 5, "s2", null, null, PickupDropOffType.REGULAR, PickupDropOffType.REGULAR, 900.0),
                new StopTime(
                        last, 8, "s2", 90_600, 90_600, PickupDropOffType.REGULAR, PickupDropOffType.REGULAR, 1800.0),
                // Without any times the trip never runs.
                new StopTime(first, 1, "s2", null, null, PickupDropOffType.REGULAR, PickupDropOffType.REGULAR, null));
        load(ROUTES_AFTER, STOPS_AFTER, SHAPE_POINTS_AFTER, TRIPS_AFTER, CALENDARS_AFTER, DATES_AFTER, stopTimes);

        assertThat(jdbc.sql("SELECT concat_ws(':', trip_id, service_id, first_departure, last_arrival)"
                                + " FROM timetable.trip_spans ORDER BY trip_id")
                        .query(String.class)
                        .list())
                .containsExactly("2.T2.U2.1.H:T2:90060:90600");
    }

    @Test
    void detectsAnUpgradeThatAddedTheTripSpans() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);
        assertThat(store.needsImport()).isFalse();
        jdbc.sql("DELETE FROM timetable.trip_spans").update();
        assertThat(store.needsImport()).isTrue();
    }

    @Test
    void detectsAnUpgradeThatAddedTheStopTimes() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE, TRIPS_BEFORE, CALENDARS_BEFORE, DATES_BEFORE);
        assertThat(store.needsImport()).isFalse();
        jdbc.sql("DELETE FROM timetable.stop_times").update();
        assertThat(store.needsImport()).isTrue();
    }

    private List<@Nullable String> runningServices(LocalDate day) {
        return jdbc.sql("SELECT service_id FROM pulse.running_services(:day) ORDER BY service_id")
                .param("day", day)
                .query(String.class)
                .list();
    }

    private List<@Nullable String> stopModes() {
        return jdbc.sql("SELECT stop_id || ':' || types::text FROM timetable.stop_modes ORDER BY stop_id")
                .query(String.class)
                .list();
    }

    private List<@Nullable StopTime> stopTimes() {
        return jdbc.sql("SELECT * FROM timetable.stop_times ORDER BY trip_id, sequence")
                .query(StopTime.class)
                .list();
    }

    private static StopTime stopTime(
            String trip, int sequence, String stop, PickupDropOffType pickup, PickupDropOffType dropOff) {
        return new StopTime(trip, sequence, stop, 28_800, 28_800, pickup, dropOff, null);
    }

    private void load(
            List<Route> routes,
            List<Stop> stops,
            List<ShapePoint> shapePoints,
            List<Trip> trips,
            List<ServiceCalendar> calendars,
            List<CalendarDate> dates)
            throws IOException {
        load(
                routes,
                stops,
                shapePoints,
                trips,
                calendars,
                dates,
                trips.stream()
                        .map(trip -> stopTime(
                                trip.id(),
                                1,
                                stops.getFirst().id(),
                                PickupDropOffType.REGULAR,
                                PickupDropOffType.REGULAR))
                        .toList());
    }

    private void load(
            List<Route> routes,
            List<Stop> stops,
            List<ShapePoint> shapePoints,
            List<Trip> trips,
            List<ServiceCalendar> calendars,
            List<CalendarDate> dates,
            List<StopTime> stopTimes)
            throws IOException {
        store.replace(staging -> {
            staging.copy("routes", Route.class, routes::forEach);
            staging.copy("stops", Stop.class, stops::forEach);
            staging.copy("shape_points", ShapePoint.class, shapePoints::forEach);
            staging.copy("trips", Trip.class, trips::forEach);
            staging.copy("service_calendars", ServiceCalendar.class, calendars::forEach);
            staging.copy("calendar_dates", CalendarDate.class, dates::forEach);
            staging.stopTimes(stopTimes::forEach);
        });
    }

    private List<@Nullable Route> routes() {
        return jdbc.sql("SELECT * FROM timetable.routes ORDER BY id")
                .query(Route.class)
                .list();
    }

    private List<@Nullable Stop> stops() {
        return jdbc.sql("SELECT * FROM timetable.stops ORDER BY id")
                .query(Stop.class)
                .list();
    }

    private List<@Nullable ShapePoint> shapePoints() {
        return jdbc.sql("SELECT * FROM timetable.shape_points ORDER BY shape_id, sequence")
                .query(ShapePoint.class)
                .list();
    }

    private List<@Nullable Trip> trips() {
        return jdbc.sql("SELECT * FROM timetable.trips ORDER BY id")
                .query(Trip.class)
                .list();
    }

    private List<@Nullable ServiceCalendar> calendars() {
        return jdbc.sql("SELECT * FROM timetable.service_calendars ORDER BY service_id")
                .query(ServiceCalendar.class)
                .list();
    }

    private List<@Nullable CalendarDate> dates() {
        return jdbc.sql("SELECT * FROM timetable.calendar_dates ORDER BY service_id, date")
                .query(CalendarDate.class)
                .list();
    }

    private List<String> schemas() {
        return jdbc.sql("""
                        SELECT schema_name FROM information_schema.schemata
                        WHERE schema_name IN ('staging', 'timetable', 'timetable_old')
                        """)
                .query((row, number) -> row.getString("schema_name"))
                .list();
    }
}
