package com.vladdancea.viennapulse.api;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

// The same setup as the controller tests, so that all of them share one application and database.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class DatabaseFailureTest {
    @Autowired
    RestTestClient client;

    @Autowired
    JdbcClient jdbc;

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
}
