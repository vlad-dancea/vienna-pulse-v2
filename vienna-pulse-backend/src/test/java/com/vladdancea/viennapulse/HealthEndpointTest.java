package com.vladdancea.viennapulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

// The container healthcheck in production asks the readiness probe on the management port, and the
// internet only reaches the server port.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class HealthEndpointTest {
    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    TimetableFixture timetable;

    @Autowired
    JdbcClient jdbc;

    @Test
    void reportsReadinessOnTheManagementPort() throws IOException, InterruptedException {
        var response = get(managementPort, "/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void reportsAnOldTimetableOnlyOutsideReadiness() throws IOException, InterruptedException {
        // Without any import or timetable, the timetable is too old for any clock.
        timetable.clear();
        jdbc.sql("DELETE FROM public.imports").update();

        var response = get(managementPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(503);
        var timetable = JsonMapper.shared().readTree(response.body()).at("/components/timetable/status");
        assertThat(timetable.asString()).isEqualTo("DOWN");
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode())
                .isEqualTo(200);
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
