package com.vladdancea.viennapulse.importer.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.stoptime.StopTime;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;
import de.siegmar.fastcsv.writer.QuoteStrategies;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;

/// The schema a new timetable is loaded into before it replaces the live one.
@Slf4j
public final class Staging {
    private final DataSource dataSource;
    private final Map<String, Long> rowCounts = new TreeMap<>();

    Staging(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /// Hands records to a consumer one at a time, so a file too large for memory can stream straight
    /// into its table. A list passes its `forEach`.
    @FunctionalInterface
    public interface Rows<T> {
        void forEach(Consumer<T> row) throws IOException;
    }

    /// Streams `rows` into `table` with PostgreSQL's `COPY`, one row per record. Each record component
    /// fills the column of the same name in snake case, so `shortName` fills `short_name`. Components
    /// must be strings, numbers, booleans, [java.time.LocalDate]s or enums, which are stored by name.
    public <T extends Record> void copy(String table, Class<T> type, Rows<T> rows) throws IOException {
        rowCounts.put(table, copy("staging", table, type, rows));
    }

    /// Streams `stop_times.txt` into its table, then classifies every platform by the route types of
    /// the calls where passengers can board or alight and records when each trip runs. The
    /// references are checked on every call, even those without passengers.
    public void stopTimes(Rows<StopTime> rows) throws IOException {
        copy("stop_times", StopTime.class, rows);
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("ANALYZE staging.stop_times").update();
        var invalid = jdbc.sql("""
                SELECT 'stop_times.txt references unknown trip, stop or route: '
                       || c.trip_id || ', ' || c.stop_id
                FROM staging.stop_times c
                LEFT JOIN staging.trips t ON t.id = c.trip_id
                LEFT JOIN staging.stops s ON s.id = c.stop_id
                LEFT JOIN staging.routes r ON r.id = t.route_id
                WHERE t.id IS NULL OR s.id IS NULL OR r.id IS NULL
                LIMIT 1
                """).query(String.class).optional();
        if (invalid.isPresent()) {
            throw new FeedFormatException(invalid.get());
        }
        var stopModes = jdbc.sql("""
                INSERT INTO staging.stop_modes (stop_id, types)
                SELECT s.id, coalesce(m.types, ARRAY[]::text[])
                FROM staging.stops s
                LEFT JOIN (
                    SELECT c.stop_id, array_agg(DISTINCT r.type ORDER BY r.type) AS types
                    FROM staging.stop_times c
                    JOIN staging.trips t ON t.id = c.trip_id
                    JOIN staging.routes r ON r.id = t.route_id
                    WHERE c.pickup_type <> 'NONE' OR c.drop_off_type <> 'NONE'
                    GROUP BY c.stop_id
                ) m ON m.stop_id = s.id
                """).update();
        rowCounts.put("stop_modes", (long) stopModes);
        // A trip none of whose calls has a time cannot be placed on the map at any moment.
        var tripSpans = jdbc.sql("""
                INSERT INTO staging.trip_spans (trip_id, service_id, first_departure, last_arrival)
                SELECT t.id, t.service_id, min(c.departure_time), max(c.arrival_time)
                FROM staging.stop_times c
                JOIN staging.trips t ON t.id = c.trip_id
                GROUP BY t.id, t.service_id
                HAVING min(c.departure_time) IS NOT NULL AND max(c.arrival_time) IS NOT NULL
                """).update();
        rowCounts.put("trip_spans", (long) tripSpans);
    }

    private <T extends Record> long copy(String schema, String table, Class<T> type, Rows<T> rows) throws IOException {
        var components = type.getRecordComponents();
        var columns = Arrays.stream(components)
                .map(component -> quoted(snakeCase(component.getName())))
                .collect(joining(", "));
        var sql = "COPY %s.%s (%s) FROM STDIN (FORMAT csv)".formatted(quoted(schema), quoted(table), columns);
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            var copy = new PGCopyOutputStream(connection.unwrap(PGConnection.class), sql);
            try {
                write(copy, components, rows);
                var count = copy.endCopy();
                log.info("Copied {} rows into {}.{}", count, schema, table);
                return count;
            } catch (IOException | SQLException | RuntimeException exception) {
                // An unfinished COPY blocks the connection, so the transaction could not even roll back.
                if (copy.isActive()) {
                    copy.cancelCopy();
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new UncategorizedSQLException("Copying into " + schema + "." + table, sql, exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /// The rows loaded so far into each table, by table name.
    Map<String, Long> rowCounts() {
        return new TreeMap<>(rowCounts);
    }

    private static <T extends Record> void write(PGCopyOutputStream copy, RecordComponent[] components, Rows<T> rows)
            throws IOException {
        // Quoting empty strings keeps them apart from unquoted empty fields, which COPY reads as null.
        var csv = CsvWriter.builder()
                .quoteStrategy(QuoteStrategies.EMPTY)
                .lineDelimiter(LineDelimiter.LF)
                .build(copy, UTF_8);
        var values = new @Nullable String[components.length];
        rows.forEach(row -> {
            for (int i = 0; i < components.length; i++) {
                values[i] = text(valueOf(components[i], row));
            }
            csv.writeRecord(values);
        });
        csv.flush();
    }

    private static @Nullable Object valueOf(RecordComponent component, Record row) {
        try {
            return component.getAccessor().invoke(row);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read " + component.getName() + " of " + row, exception);
        }
    }

    private static @Nullable String text(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Enum<?> constant -> constant.name();
            default -> value.toString();
        };
    }

    private static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    static String quoted(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
