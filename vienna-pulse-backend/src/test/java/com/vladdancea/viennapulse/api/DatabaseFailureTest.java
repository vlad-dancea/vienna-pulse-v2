package com.vladdancea.viennapulse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

// The same setup as the controller tests, so that all of them share one application and database.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class DatabaseFailureTest {
    // The limits in ApiConfiguration and application.properties, plus time for the request itself.
    private static final Duration QUERY_LIMIT = Duration.ofSeconds(5);
    private static final Duration CONNECTION_WAIT = Duration.ofSeconds(2);
    private static final Duration NETWORK_LIMIT = Duration.ofSeconds(10);
    private static final Duration MARGIN = Duration.ofSeconds(2);

    @Autowired
    RestTestClient client;

    @Autowired
    HikariDataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    // Without a limit the query would wait for the lock until the test gives up.
    @Test
    @Timeout(20)
    void answersAQueryOverTheTimeLimitWithA503() throws SQLException {
        // A lock that no API query can get past keeps the stops query waiting until it is cancelled.
        try (var lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.execute("LOCK TABLE timetable.stops IN ACCESS EXCLUSIVE MODE");
            }
            var took = timeUnavailable("/api/stops");
            assertThat(took).isBetween(QUERY_LIMIT, QUERY_LIMIT.plus(MARGIN));
            lock.rollback();
        }
    }

    // Without the network limit the request would wait for the frozen database until the test gives up.
    @Test
    @Timeout(30)
    void answersWithA503WhileTheDatabaseIsFrozen() throws Exception {
        var postgres = TestcontainersConfiguration.POSTGRES;
        var docker = postgres.getDockerClient();
        try (var lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.execute("LOCK TABLE timetable.stops IN ACCESS EXCLUSIVE MODE");
            }
            // Two at once, because PgJDBC sends their cancellations one after the other.
            var first = CompletableFuture.supplyAsync(() -> timeUnavailable("/api/stops"));
            var second = CompletableFuture.supplyAsync(() -> timeUnavailable("/api/stops"));
            try {
                await().until(() -> stopsQueriesWaitingForTheLock() == 2);
                docker.pauseContainerCmd(postgres.getContainerId()).exec();
                try {
                    // A frozen database answers neither the queries nor their cancellations.
                    assertThat(first.get()).isBetween(NETWORK_LIMIT, NETWORK_LIMIT.plus(MARGIN));
                    assertThat(second.get()).isBetween(NETWORK_LIMIT, NETWORK_LIMIT.plus(MARGIN));
                    // The pool checks its idle connections before handing one out, and gives up on them all.
                    assertThat(timeUnavailable("/api/stops")).isBetween(CONNECTION_WAIT, CONNECTION_WAIT.plus(MARGIN));
                } finally {
                    docker.unpauseContainerCmd(postgres.getContainerId()).exec();
                }
            } finally {
                // A request still running after a failure would hold a connection into the next test. The
                // query limit ends it while the lock is still held.
                CompletableFuture.allOf(first, second)
                        .handle((ignored, failure) -> null)
                        .join();
            }
            lock.rollback();
        }
    }

    @Test
    void answersARequestWithoutAFreeConnectionWithA503() throws SQLException {
        var held = new ArrayList<Connection>();
        try {
            while (held.size() < dataSource.getMaximumPoolSize()) {
                held.add(dataSource.getConnection());
            }
            var took = timeUnavailable("/api/stops");
            assertThat(took).isBetween(CONNECTION_WAIT, CONNECTION_WAIT.plus(MARGIN));
        } finally {
            for (var connection : held) {
                connection.close();
            }
        }
    }

    @Test
    void answersAnyOtherDatabaseFailureWithA500() {
        // Breaks the stops query as a mistake in its SQL would.
        jdbc.sql("ALTER TABLE timetable.stop_modes RENAME TO renamed_stop_modes")
                .update();
        try {
            client.get()
                    .uri("/api/stops")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(500)
                    .expectHeader()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader()
                    .cacheControl(CacheControl.noStore())
                    .expectBody()
                    .json("""
                            {"title": "Internal Server Error", "status": 500, "instance": "/api/stops",
                             "detail": "Reading the timetable failed"}
                            """, JsonCompareMode.STRICT);
        } finally {
            jdbc.sql("ALTER TABLE timetable.renamed_stop_modes RENAME TO stop_modes")
                    .update();
        }
    }

    private int stopsQueriesWaitingForTheLock() {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND query LIKE '%timetable.stops%'
                        """).query(Integer.class).single();
    }

    private Duration timeUnavailable(String path) {
        var start = System.nanoTime();
        client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectHeader()
                .valueEquals(HttpHeaders.RETRY_AFTER, "5")
                .expectBody()
                .json("""
                        {"title": "Service Unavailable", "status": 503, "instance": "%s",
                         "detail": "The timetable database is not answering, try again in a few seconds"}
                        """.formatted(path), JsonCompareMode.STRICT);
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
