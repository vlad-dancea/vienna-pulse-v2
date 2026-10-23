package com.vladdancea.viennapulse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/// Runs tests against PostgreSQL in a container, from the image `compose.yaml` runs in development.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    // Reading the image from compose.yaml leaves one pin to update, which Renovate already does.
    private static final Pattern POSTGRES_IMAGE =
            Pattern.compile("^\\s*image:\\s*(postgres:\\S+)\\s*$", Pattern.MULTILINE);

    // One container for every Spring context of the test run, which would otherwise each start their
    // own. A closing context stops it, which Spring's context cache only does when the run ends.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(postgresImage());

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
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
