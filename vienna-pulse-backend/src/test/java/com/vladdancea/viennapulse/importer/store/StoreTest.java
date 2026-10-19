package com.vladdancea.viennapulse.importer.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.importer.read.route.Route;
import com.vladdancea.viennapulse.importer.read.route.RouteType;
import com.vladdancea.viennapulse.importer.read.shape.ShapePoint;
import com.vladdancea.viennapulse.importer.read.stop.Stop;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
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

    // Commas, quotes and nulls must survive the trip through COPY.
    private static final List<Route> ROUTES_AFTER = List.of(
            new Route("D", null, "D", "Nußdorf, \"Beethovengang\"", RouteType.TRAM, null, null),
            new Route("U2", "wl", "U2", null, RouteType.SUBWAY, "A762A3", "FFFFFF"));
    private static final List<Stop> STOPS_AFTER = List.of(new Stop("s2", "Stephansplatz", 48.20845, 16.37212, null));
    private static final List<ShapePoint> SHAPE_POINTS_AFTER = List.of(
            new ShapePoint("D.1.H", 48.25638, 16.36195, 1, null),
            new ShapePoint("D.1.H", 48.25551, 16.36277, 2, null),
            new ShapePoint("U2.1.H", 48.21950, 16.39278, 1, 0.0));

    @Autowired
    Store store;

    @Autowired
    JdbcClient jdbc;

    @Test
    void replacesTheWholeTimetable() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE);
        load(ROUTES_AFTER, STOPS_AFTER, SHAPE_POINTS_AFTER);

        assertThat(routes()).isEqualTo(ROUTES_AFTER);
        assertThat(stops()).isEqualTo(STOPS_AFTER);
        assertThat(shapePoints()).isEqualTo(SHAPE_POINTS_AFTER);
    }

    @Test
    void keepsThePreviousTimetableWhenTheLoadFails() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE);

        assertThatThrownBy(() -> store.replace(staging -> {
                    staging.copy("routes", Route.class, ROUTES_AFTER::forEach);
                    throw new IOException("feed cut off");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("feed cut off");

        assertThat(routes()).isEqualTo(ROUTES_BEFORE);
        assertThat(stops()).isEqualTo(STOPS_BEFORE);
        assertThat(shapePoints()).isEqualTo(SHAPE_POINTS_BEFORE);
        assertThat(schemas()).containsExactly("timetable");
    }

    // The reader leaves checks across rows to whoever groups the points, which is the primary key.
    @Test
    void rejectsAShapeThatRepeatsASequenceNumber() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE);
        var repeated = List.of(
                new ShapePoint("D.1.H", 48.25638, 16.36195, 1, null),
                new ShapePoint("D.1.H", 48.25551, 16.36277, 1, null));

        assertThatThrownBy(() -> load(ROUTES_AFTER, STOPS_AFTER, repeated))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("Key (shape_id, sequence)=(D.1.H, 1) already exists.");

        assertThat(shapePoints()).isEqualTo(SHAPE_POINTS_BEFORE);
    }

    @Test
    void rejectsALoadThatSkipsATable() throws IOException {
        load(ROUTES_BEFORE, STOPS_BEFORE, SHAPE_POINTS_BEFORE);

        assertThatThrownBy(() -> store.replace(staging -> staging.copy("routes", Route.class, ROUTES_AFTER::forEach)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The import loaded nothing into [shape_points, stops]");

        assertThat(routes()).isEqualTo(ROUTES_BEFORE);
    }

    private void load(List<Route> routes, List<Stop> stops, List<ShapePoint> shapePoints) throws IOException {
        store.replace(staging -> {
            staging.copy("routes", Route.class, routes::forEach);
            staging.copy("stops", Stop.class, stops::forEach);
            staging.copy("shape_points", ShapePoint.class, shapePoints::forEach);
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

    private List<String> schemas() {
        return jdbc.sql("""
                        SELECT schema_name FROM information_schema.schemata
                        WHERE schema_name IN ('staging', 'timetable', 'timetable_old')
                        """)
                .query((row, number) -> row.getString("schema_name"))
                .list();
    }
}
