package com.vladdancea.viennapulse.api;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springdoc.core.service.GenericResponseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

// springdoc is only a test dependency, so production serves no description. Sorted keys keep the
// file the same from one run to the next.
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "springdoc.writer-with-order-by-keys=true",
            "springdoc.writer-with-default-pretty-printer=true",
            "springdoc.default-produces-media-type=application/json"
        })
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, ApiDescriptionTest.Description.class})
class ApiDescriptionTest {
    // The frontend generates its types from this file, so a change to a response shows up in its
    // check too.
    private static final Path DESCRIPTION = Path.of("openapi.json");

    @Autowired
    RestTestClient client;

    @Test
    void matchesTheCommittedDescription() throws IOException {
        var served = requireNonNull(client.get()
                        .uri("/v3/api-docs")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody())
                + "\n";
        if (Boolean.getBoolean("updateApiDescription")) {
            Files.writeString(DESCRIPTION, served);
        }

        assertThat(Files.readString(DESCRIPTION))
                .as("openapi.json describes another API. Run `mise run openapi` to update it and the frontend types.")
                .isEqualTo(served);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Description {
        @Bean
        RecordSchemas recordSchemas() {
            return new RecordSchemas();
        }

        @Bean
        CachedBodies cachedBodies(GenericResponseService responses) {
            return new CachedBodies(responses);
        }

        @Bean
        ErrorResponses errorResponses() {
            return new ErrorResponses();
        }

        // Without a server, springdoc writes the test's random port into the description.
        @Bean
        OpenAPI openApi() {
            return new OpenAPI()
                    .info(new Info().title("Vienna Pulse API").version("1"))
                    .servers(List.of(new Server().url("https://pulse-api.vladdancea.com")));
        }
    }
}
