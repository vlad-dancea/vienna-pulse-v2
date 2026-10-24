package com.vladdancea.viennapulse.importer.store;

import static com.vladdancea.viennapulse.importer.store.Staging.quoted;
import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/// Replaces the timetable in PostgreSQL. The new timetable is loaded into a `staging` schema, which
/// is then renamed to `timetable` in the same transaction: readers see either the previous
/// timetable or the new one, never a mix, and a failed import leaves the previous one in place.
@Component
@Slf4j
@RequiredArgsConstructor
public class Store {
    // Identifies the import among PostgreSQL's advisory locks. Any number works that nothing else uses.
    private static final long IMPORT_LOCK = 0x7069_6d70_6f72_74L;

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    /// Fills the staging schema. Runs inside the import transaction.
    @FunctionalInterface
    public interface Load {
        void into(Staging staging) throws IOException;
    }

    /// Runs in the import transaction after the swap with the rows loaded into each table, by table
    /// name, so whatever it writes commits with the new timetable or rolls back with it.
    @FunctionalInterface
    public interface Swapped {
        void with(Map<String, Long> rowCounts);
    }

    /// Loads a new timetable with `load`, swaps it in and calls `swapped`. Fails with
    /// [CannotAcquireLockException] if another import is running, and with [IllegalStateException] if
    /// `load` leaves a table unloaded.
    public void replace(Load load, Swapped swapped) throws IOException {
        try {
            transaction.executeWithoutResult(status -> swapIn(load, swapped));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        dropPrevious();
    }

    /// Whether a timetable table is empty: every one is in a new database, and so is a table that a
    /// migration has just added.
    public boolean needsImport() {
        var empty = timetableTables().stream()
                .map(table -> "NOT EXISTS (SELECT FROM timetable.%s)".formatted(quoted(table)))
                .collect(joining(" OR "));
        return jdbc.sql("SELECT " + empty).query(Boolean.class).single();
    }

    private void swapIn(Load load, Swapped swapped) {
        var locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:lock)")
                .param("lock", IMPORT_LOCK)
                .query(Boolean.class)
                .single();
        if (!locked) {
            throw new CannotAcquireLockException("Another timetable import is running");
        }
        // Left behind when dropping it timed out after the previous import.
        jdbc.sql("DROP SCHEMA IF EXISTS timetable_old CASCADE").update();

        var tables = createStaging();
        var staging = new Staging(dataSource);
        try {
            load.into(staging);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var rowCounts = staging.rowCounts();
        var unloaded = new ArrayList<>(tables);
        unloaded.removeAll(rowCounts.keySet());
        if (!unloaded.isEmpty()) {
            throw new IllegalStateException("The import loaded nothing into " + unloaded);
        }

        jdbc.sql("ALTER SCHEMA timetable RENAME TO timetable_old").update();
        jdbc.sql("ALTER SCHEMA staging RENAME TO timetable").update();
        swapped.with(rowCounts);
    }

    // Copies every table of the live timetable, so the Flyway migrations stay the only place that
    // defines their structure.
    private List<String> createStaging() {
        var tables = timetableTables();
        jdbc.sql("CREATE SCHEMA staging").update();
        for (var table : tables) {
            jdbc.sql("CREATE TABLE staging.%1$s (LIKE timetable.%1$s INCLUDING ALL)".formatted(quoted(table)))
                    .update();
        }
        return tables;
    }

    private List<String> timetableTables() {
        return jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'timetable' AND table_type = 'BASE TABLE'
                        ORDER BY table_name
                        """).query((row, number) -> row.getString("table_name")).list();
    }

    private void dropPrevious() {
        try {
            transaction.executeWithoutResult(status -> {
                // Dropping waits for queries still reading the previous timetable, but not forever.
                jdbc.sql("SET LOCAL lock_timeout = '10s'").update();
                jdbc.sql("DROP SCHEMA timetable_old CASCADE").update();
            });
        } catch (DataAccessException | TransactionException exception) {
            // The new timetable has committed, so this failure must not fail the import.
            log.warn("Could not drop the previous timetable, the next import will", exception);
        }
    }
}
