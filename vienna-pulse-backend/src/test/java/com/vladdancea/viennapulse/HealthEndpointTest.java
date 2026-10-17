package com.vladdancea.viennapulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

// The container healthcheck in production asks the readiness probe on the management port, and the
// internet only reaches the server port.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class HealthEndpointTest {
    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @Test
    void reportsReadinessOnTheManagementPort() throws IOException, InterruptedException {
        var response = get(managementPort, "/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void keepsTheActuatorOffTheServerPort() throws IOException, InterruptedException {
        assertThat(get(serverPort, "/actuator/health").statusCode()).isEqualTo(404);
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .build();
            return http.send(request, BodyHandlers.ofString());
        }
    }
}
