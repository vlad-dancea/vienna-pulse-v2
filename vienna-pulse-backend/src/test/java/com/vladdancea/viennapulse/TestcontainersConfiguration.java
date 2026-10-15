package com.vladdancea.viennapulse;

import java.io.IOException;
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

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() throws IOException {
        var compose = Files.readString(Path.of("compose.yaml"));
        var image = POSTGRES_IMAGE
                .matcher(compose)
                .results()
                .map(match -> match.group(1))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("compose.yaml runs no postgres image"));
        // Testcontainers does not recognise a name pinned by tag and digest as PostgreSQL by itself.
        return new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
    }
}
