package com.vladdancea.viennapulse.importer.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

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
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyOutputStream;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;

/// The schema a new timetable is loaded into before it replaces the live one.
@Slf4j
public final class Staging {
    private final DataSource dataSource;
    private final Set<String> loadedTables = new HashSet<>();

    Staging(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /// Streams `rows` into `table` with PostgreSQL's `COPY`, one row per record. Each record component
    /// fills the column of the same name in snake case, so `shortName` fills `short_name`. Components
    /// must be strings, numbers or enums, which are stored by name.
    public <T extends Record> void copy(String table, Class<T> type, Iterable<? extends T> rows) throws IOException {
        var components = type.getRecordComponents();
        var columns = Arrays.stream(components)
                .map(component -> quoted(snakeCase(component.getName())))
                .collect(joining(", "));
        var sql = "COPY staging.%s (%s) FROM STDIN (FORMAT csv)".formatted(quoted(table), columns);
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            var copy = new PGCopyOutputStream(connection.unwrap(PGConnection.class), sql);
            try {
                write(copy, components, rows);
                var count = copy.endCopy();
                loadedTables.add(table);
                log.info("Copied {} rows into staging.{}", count, table);
            } catch (IOException | SQLException | RuntimeException exception) {
                // An unfinished COPY blocks the connection, so the transaction could not even roll back.
                if (copy.isActive()) {
                    copy.cancelCopy();
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new UncategorizedSQLException("Copying into staging." + table, sql, exception);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    Set<String> loadedTables() {
        return Set.copyOf(loadedTables);
    }

    private static void write(PGCopyOutputStream copy, RecordComponent[] components, Iterable<? extends Record> rows)
            throws IOException {
        // Quoting empty strings keeps them apart from unquoted empty fields, which COPY reads as null.
        var csv = CsvWriter.builder()
                .quoteStrategy(QuoteStrategies.EMPTY)
                .lineDelimiter(LineDelimiter.LF)
                .build(copy, UTF_8);
        var values = new @Nullable String[components.length];
        for (var row : rows) {
            for (int i = 0; i < components.length; i++) {
                values[i] = text(valueOf(components[i], row));
            }
            csv.writeRecord(values);
        }
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
