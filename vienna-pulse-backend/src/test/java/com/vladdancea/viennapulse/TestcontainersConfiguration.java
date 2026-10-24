package com.vladdancea.viennapulse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/// Runs tests against PostgreSQL in a container, from the image `compose.yaml` runs in development,
/// with a [TimetableFixture] to fill it and a [TestClock] that each test sets.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    // Reading the image from compose.yaml leaves one pin to update, which Renovate already does.
    private static final Pattern POSTGRES_IMAGE =
            Pattern.compile("^\\s*image:\\s*(postgres:\\S+)\\s*$", Pattern.MULTILINE);

    // One container for every Spring context of the test run, which would otherwise each start their
    // own. It is no bean, because a context stops its container beans when it closes, as when the
    // context cache evicts it, while other cached contexts still use them. Testcontainers removes it
    // when the test run ends. Public for the tests that freeze the database.
    public static final PostgreSQLContainer POSTGRES = startPostgres();

    @Bean
    JdbcConnectionDetails postgres() {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return POSTGRES.getUsername();
            }

            @Override
            public String getPassword() {
                return POSTGRES.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return POSTGRES.getJdbcUrl();
            }
        };
    }

    // Primary instead of a replacement for the application's clock, so that every test class shares
    // this configuration, and with it one cached context.
    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    @Bean
    TimetableFixture timetableFixture(JdbcClient jdbc, ObjectProvider<CacheManager> caches) {
        return new TimetableFixture(jdbc, caches);
    }

    private static PostgreSQLContainer startPostgres() {
        var postgres = new PostgreSQLContainer(postgresImage());
        postgres.start();
        return postgres;
    }

    private static DockerImageName postgresImage() {
        String compose;
        try {
            compose = Files.readString(Path.of("compose.yaml"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var image = POSTGRES_IMAGE
                .matcher(compose)
                .results()
                .map(match -> match.group(1))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("compose.yaml runs no postgres image"));
        // Testcontainers does not recognise a name pinned by tag and digest as PostgreSQL by itself.
        return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
    }
}
