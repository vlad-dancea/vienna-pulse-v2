package com.vladdancea.viennapulse.importer.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.stopcall.StopCall;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;
import de.siegmar.fastcsv.writer.QuoteStrategies;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
    private final Set<String> loadedTables = new HashSet<>();

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
        copy("staging", table, type, rows);
        loadedTables.add(table);
    }

    /// Validates every call before classifying platforms, so even non-passenger calls cannot hide
    /// broken references; the temporary calls share the import's connection and transaction.
    public void stopModes(Rows<StopCall> rows) throws IOException {
        var jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                CREATE TEMP TABLE pulse_stop_calls (
                    trip_id text NOT NULL, stop_id text NOT NULL, eligible boolean NOT NULL
                ) ON COMMIT DROP
                """).update();
        copy("pg_temp", "pulse_stop_calls", StopCall.class, rows);
        jdbc.sql("ANALYZE pg_temp.pulse_stop_calls").update();
        var invalid = jdbc.sql("""
                SELECT 'stop_times.txt references unknown trip, stop or route: '
                       || c.trip_id || ', ' || c.stop_id
                FROM pg_temp.pulse_stop_calls c
                LEFT JOIN staging.trips t ON t.id = c.trip_id
                LEFT JOIN staging.stops s ON s.id = c.stop_id
                LEFT JOIN staging.routes r ON r.id = t.route_id
                WHERE t.id IS NULL OR s.id IS NULL OR r.id IS NULL
                LIMIT 1
                """).query(String.class).optional();
        if (invalid.isPresent()) {
            throw new FeedFormatException(invalid.get());
        }
        jdbc.sql("""
                INSERT INTO staging.stop_modes (stop_id, types)
                SELECT s.id, coalesce(m.types, ARRAY[]::text[])
                FROM staging.stops s
                LEFT JOIN (
                    SELECT c.stop_id, array_agg(DISTINCT r.type ORDER BY r.type) AS types
                    FROM pg_temp.pulse_stop_calls c
                    JOIN staging.trips t ON t.id = c.trip_id
                    JOIN staging.routes r ON r.id = t.route_id
                    WHERE c.eligible
                    GROUP BY c.stop_id
                ) m ON m.stop_id = s.id
                """).update();
        loadedTables.add("stop_modes");
    }

    private <T extends Record> void copy(String schema, String table, Class<T> type, Rows<T> rows) throws IOException {
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

    Set<String> loadedTables() {
        return Set.copyOf(loadedTables);
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
