package com.vladdancea.viennapulse;

import static java.util.Objects.requireNonNull;

import com.vladdancea.viennapulse.importer.read.calendar.CalendarDate;
import com.vladdancea.viennapulse.importer.read.calendar.ExceptionType;
import com.vladdancea.viennapulse.importer.read.calendar.ServiceCalendar;
import com.vladdancea.viennapulse.importer.read.route.Route;
import com.vladdancea.viennapulse.importer.read.route.RouteType;
import com.vladdancea.viennapulse.importer.read.shape.ShapePoint;
import com.vladdancea.viennapulse.importer.read.stop.Stop;
import com.vladdancea.viennapulse.importer.read.stoptime.PickupDropOffType;
import com.vladdancea.viennapulse.importer.read.trip.Trip;
import com.vladdancea.viennapulse.importer.store.Store;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;

/// Writes the timetable rows a test needs straight into the database, without an import.
public class TimetableFixture {
    private final JdbcClient jdbc;
    // Slice tests such as @JdbcTest have no caches.
    private final ObjectProvider<CacheManager> caches;

    public TimetableFixture(JdbcClient jdbc, ObjectProvider<CacheManager> caches) {
        this.jdbc = jdbc;
        this.caches = caches;
    }

    /// A call of a trip, with times written as in the feed, where hours can pass 24.
    public record Call(
            int sequence,
            String stop,
            @Nullable String arrival,
            @Nullable String departure,
            PickupDropOffType pickup,
            @Nullable Double distance) {
        /// Arrives and departs at `time`, and lets passengers on and off.
        public static Call at(int sequence, String stop, String time) {
            return new Call(sequence, stop, time, time, PickupDropOffType.REGULAR, null);
        }

        public Call withPickup(PickupDropOffType pickup) {
            return new Call(sequence, stop, arrival, departure, pickup, distance);
        }
    }

    /// Empties every table of the timetable, found the way the import finds them, so a table added
    /// later is emptied too. Also forgets the API's cached responses, which only an import refreshes
    /// and which every test class shares through one Spring context.
    public void clear() {
        caches.ifAvailable(manager -> manager.getCacheNames()
                .forEach(name -> requireNonNull(manager.getCache(name)).clear()));
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'timetable' AND table_type = 'BASE TABLE'
                        """)
                .query((row, number) -> "timetable." + row.getString("table_name"))
                .list();
        jdbc.sql("TRUNCATE " + String.join(", ", tables)).update();
    }

    /// Swaps in an empty timetable through `store`, the way the daily import swaps in the next one, so
    /// that the API sees a new timetable version.
    public void importNothing(Store store) throws IOException {
        store.replace(
                staging -> {
                    staging.copy("routes", Route.class, row -> {});
                    staging.copy("stops", Stop.class, row -> {});
                    staging.copy("shape_points", ShapePoint.class, row -> {});
                    staging.copy("trips", Trip.class, row -> {});
                    staging.copy("service_calendars", ServiceCalendar.class, row -> {});
                    staging.copy("calendar_dates", CalendarDate.class, row -> {});
                    staging.stopTimes(row -> {});
                },
                rowCounts -> {});
    }

    /// Adds a stop in the centre of Vienna.
    public void addStop(String id, String name) {
        addStop(id, name, 48.2, 16.37);
    }

    public void addStop(String id, String name, double latitude, double longitude) {
        jdbc.sql("INSERT INTO timetable.stops (id, name, latitude, longitude) VALUES (?, ?, ?, ?)")
                .params(id, name, latitude, longitude)
                .update();
    }

    public void addRoute(
            String id,
            String shortName,
            @Nullable String longName,
            RouteType type,
            @Nullable String color,
            @Nullable String textColor) {
        jdbc.sql("""
                        INSERT INTO timetable.routes (id, short_name, long_name, type, color, text_color)
                        VALUES (:id, :shortName, :longName, :type, :color, :textColor)
                        """)
                .param("id", id)
                .param("shortName", shortName)
                .param("longName", longName)
                .param("type", type.name())
                .param("color", color)
                .param("textColor", textColor)
                .update();
    }

    /// Adds a service that runs on the weekday of `day`, from a week before it to a week after.
    public void addWeeklyService(String id, LocalDate day) {
        addWeeklyService(id, day.getDayOfWeek(), day.minusDays(7), day.plusDays(7));
    }

    public void addWeeklyService(String id, DayOfWeek weekday, LocalDate start, LocalDate end) {
        jdbc.sql("""
                        INSERT INTO timetable.service_calendars
                        VALUES (:id, false, false, false, false, false, false, false, :start, :end)
                        """).param("id", id).param("start", start).param("end", end).update();
        jdbc.sql("UPDATE timetable.service_calendars SET %s = true WHERE service_id = :id"
                        .formatted(weekday.name().toLowerCase(Locale.ROOT)))
                .param("id", id)
                .update();
    }

    public void addException(String service, LocalDate date, ExceptionType type) {
        jdbc.sql("INSERT INTO timetable.calendar_dates VALUES (?, ?, ?)")
                .params(service, date, type.name())
                .update();
    }

    /// Adds a trip with its headsign named after it, its calls, and the span the import derives from
    /// them.
    public void addTrip(String id, String route, String service, @Nullable String shape, Call... calls) {
        addTrip(id, id, null, route, service, shape, calls);
    }

    /// Adds a trip like [#addTrip], but towards a headsign that other trips can share and without a shape.
    public void addTripTowards(String id, String headsign, String route, String service, Call... calls) {
        addTrip(id, headsign, null, route, service, null, calls);
    }

    /// Adds a trip like [#addTripTowards], in the direction with the id `direction`, 0 or 1.
    public void addTripTowards(String id, String headsign, int direction, String route, String service, Call... calls) {
        addTrip(id, headsign, direction, route, service, null, calls);
    }

    private void addTrip(
            String id,
            String headsign,
            @Nullable Integer direction,
            String route,
            String service,
            @Nullable String shape,
            Call... calls) {
        jdbc.sql("""
                        INSERT INTO timetable.trips (id, route_id, service_id, shape_id, headsign, direction_id)
                        VALUES (:id, :route, :service, :shape, :headsign, :direction)
                        """)
                .param("id", id)
                .param("headsign", headsign)
                .param("direction", direction)
                .param("route", route)
                .param("service", service)
                .param("shape", shape)
                .update();
        for (var call : calls) {
            jdbc.sql("""
                            INSERT INTO timetable.stop_times
                                (trip_id, sequence, stop_id, arrival_time, departure_time, pickup_type, drop_off_type,
                                 distance_traveled)
                            VALUES (:trip, :sequence, :stop, :arrival, :departure, :pickup, 'REGULAR', :distance)
                            """)
                    .param("trip", id)
                    .param("sequence", call.sequence())
                    .param("stop", call.stop())
                    .param("arrival", seconds(call.arrival()))
                    .param("departure", seconds(call.departure()))
                    .param("pickup", call.pickup().name())
                    .param("distance", call.distance())
                    .update();
        }
        // The same query as the import's, for one trip.
        jdbc.sql("""
                        INSERT INTO timetable.trip_spans (trip_id, service_id, first_departure, last_arrival)
                        SELECT t.id, t.service_id, min(c.departure_time), max(c.arrival_time)
                        FROM timetable.stop_times c
                        JOIN timetable.trips t ON t.id = c.trip_id
                        WHERE t.id = :id
                        GROUP BY t.id, t.service_id
                        HAVING min(c.departure_time) IS NOT NULL AND max(c.arrival_time) IS NOT NULL
                        """).param("id", id).update();
    }

    /// Adds a shape through the given points, without distances, inserting them from the last to the
    /// first so that readers must order them by sequence.
    public void addShape(String id, double... longitudesAndLatitudes) {
        for (int i = longitudesAndLatitudes.length - 2; i >= 0; i -= 2) {
            addShapePoint(id, i, longitudesAndLatitudes[i], longitudesAndLatitudes[i + 1], null);
        }
    }

    public void addShapePoint(
            String shape, int sequence, double longitude, double latitude, @Nullable Double distance) {
        jdbc.sql("""
                        INSERT INTO timetable.shape_points (shape_id, sequence, latitude, longitude, distance_traveled)
                        VALUES (:shape, :sequence, :latitude, :longitude, :distance)
                        """)
                .param("shape", shape)
                .param("sequence", sequence)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("distance", distance)
                .update();
    }

    private static @Nullable Integer seconds(@Nullable String time) {
        if (time == null) {
            return null;
        }
        var parts = time.split(":", -1);
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
    }
}
