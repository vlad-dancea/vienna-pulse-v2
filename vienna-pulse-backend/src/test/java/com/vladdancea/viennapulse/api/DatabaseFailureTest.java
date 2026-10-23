package com.vladdancea.viennapulse.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
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
    // The limit in application.properties, plus time for the request itself.
    private static final Duration CONNECTION_WAIT = Duration.ofSeconds(2);
    private static final Duration MARGIN = Duration.ofSeconds(2);

    @Autowired
    RestTestClient client;

    @Autowired
    HikariDataSource dataSource;

    @Autowired
    JdbcClient jdbc;

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
