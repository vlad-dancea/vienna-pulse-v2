package com.vladdancea.viennapulse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class TestcontainersConfigurationTest {
    // The context cache closes the contexts it evicts while the cached ones still use the database.
    @Test
    void keepsTheDatabaseRunningWhenAContextCloses() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestcontainersConfiguration.class)
                // The timetable fixture needs one, and never uses it here.
                .withBean(JdbcClient.class, () -> JdbcClient.create(new SimpleDriverDataSource()))
                .run(context -> assertThat(context).hasSingleBean(JdbcConnectionDetails.class));

        assertThat(TestcontainersConfiguration.POSTGRES.isRunning()).isTrue();
    }
}
