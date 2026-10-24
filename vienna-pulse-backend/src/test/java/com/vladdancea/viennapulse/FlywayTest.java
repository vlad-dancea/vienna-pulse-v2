package com.vladdancea.viennapulse;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

// The same setup as the controller tests, so that all of them share one application and database.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class FlywayTest {
    @Autowired
    Flyway flyway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RestTestClient client;

    @Autowired
    TimetableFixture timetable;

    // Production restarts against a database it migrated before, where the `pulse` schema exists and
    // comes first in the `pulse` user's search path. A second migration finds the history the first
    // one wrote, as the next start of the application does.
    @Test
    void migratesADatabaseItMigratedBefore() {
        assertThat(jdbc.sql("SELECT current_schema()").query(String.class).single())
                .isEqualTo("pulse");

        assertThat(flyway.migrate().migrationsExecuted).isZero();

        // Vehicles read the timetable through the functions in the `pulse` schema.
        timetable.clear();
        client.get()
                .uri("/api/vehicles")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("""
                        {"type": "FeatureCollection", "features": []}
                        """, JsonCompareMode.STRICT);
    }
}
