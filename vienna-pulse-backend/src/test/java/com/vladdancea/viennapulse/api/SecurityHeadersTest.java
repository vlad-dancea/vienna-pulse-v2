package com.vladdancea.viennapulse.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.TimetableFixture;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// The JDK client, because the test client unpacks compressed responses and drops the header.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SecurityHeadersTest {
    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "x-content-type-options", "nosniff",
            "strict-transport-security", "max-age=31536000",
            "content-security-policy", "default-src 'none'; frame-ancestors 'none'");

    @Autowired
    TimetableFixture timetable;

    @LocalServerPort
    int port;

    @BeforeEach
    void addEnoughStopsToCompress() {
        timetable.clear();
        // Tomcat leaves responses under 2 KB uncompressed.
        for (int i = 0; i < 50; i++) {
            timetable.addStop("s" + i, "Stop " + i);
        }
    }

    @Test
    void sendsThemWithCompressedResponsesAndKeepsTheirETag() throws IOException, InterruptedException {
        var response = get("/api/stops", Map.of("Accept-Encoding", "gzip"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-encoding")).hasValue("gzip");
        assertThat(response.headers().firstValue("etag"))
                .hasValueSatisfying(etag -> assertThat(etag).startsWith("W/"));
        assertSecurityHeaders(response.headers());

        var notModified = get(
                "/api/stops",
                Map.of("If-None-Match", response.headers().firstValue("etag").orElseThrow()));
        assertThat(notModified.statusCode()).isEqualTo(304);
        assertSecurityHeaders(notModified.headers());
    }

    @Test
    void sendsThemWithErrors() throws IOException, InterruptedException {
        var notFound = get("/api/stops/at:49:9999/departures", Map.of());
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.headers().firstValue("content-type")).hasValue("application/problem+json");
        assertSecurityHeaders(notFound.headers());

        // An exception without a handler is answered by a second dispatch to Spring Boot's /error.
        var failure = get("/api/failure", Map.of());
        assertThat(failure.statusCode()).isEqualTo(500);
        assertSecurityHeaders(failure.headers());
    }

    private HttpResponse<Void> get(String path, Map<String, String> headers) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(request::header);
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request.build(), BodyHandlers.discarding());
        }
    }

    private static void assertSecurityHeaders(HttpHeaders headers) {
        SECURITY_HEADERS.forEach(
                (name, value) -> assertThat(headers.allValues(name)).as(name).containsExactly(value));
    }

    // The endpoint gives this class a Spring context of its own, on the shared database.
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingEndpoint {
        @Bean
        Failure failure() {
            return new Failure();
        }
    }

    @RestController
    static class Failure {
        @GetMapping("/api/failure")
        String fail() {
            throw new IllegalStateException("A failure no handler answers");
        }
    }
}
