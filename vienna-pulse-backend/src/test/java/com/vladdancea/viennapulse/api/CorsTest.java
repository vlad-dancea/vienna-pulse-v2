package com.vladdancea.viennapulse.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class CorsTest {
    @Autowired
    RestTestClient client;

    @Test
    void letsTheSiteAndItsPreviewsReadTheApi() {
        for (var origin : new String[] {"https://pulse.vladdancea.com", "https://pr-46.pulse.vladdancea.com"}) {
            client.get()
                    .uri("/api/stops")
                    .header(HttpHeaders.ORIGIN, origin)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
                    .expectHeader()
                    .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
                    // Caches must not hand one origin's answer to another.
                    .expectHeader()
                    .values(
                            HttpHeaders.VARY,
                            values -> assertThat(String.join(",", values)).contains(HttpHeaders.ORIGIN));
        }
    }

    @Test
    void refusesOtherOriginsAndMethods() {
        client.get()
                .uri("/api/stops")
                .header(HttpHeaders.ORIGIN, "https://pulse.vladdancea.com.example.org")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        client.options()
                .uri("/api/stops")
                .header(HttpHeaders.ORIGIN, "https://pulse.vladdancea.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                .exchange()
                .expectStatus()
                .isForbidden();
    }
}
