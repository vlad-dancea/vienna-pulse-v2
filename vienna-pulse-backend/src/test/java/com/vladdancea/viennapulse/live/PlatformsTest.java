package com.vladdancea.viennapulse.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestClock;
import java.time.Duration;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PlatformsTest {
    private static final int PRATERSTERN = 60201040;

    @AutoClose
    final MonitorServer server = MonitorServer.start();

    final TestClock clock = new TestClock();

    // The live times need only the platforms, so they load without the codes, which the hourly check
    // tries again.
    @Test
    void loadsThePlatformsWithoutTheCodesAndTheCodesWithinTheHour() {
        clock.set(MonitorServer.ANSWERED_AT);
        server.removePlatformCodes();
        var platforms = new Platforms(properties(), RestClient.builder(), clock);

        platforms.load();

        assertThat(platforms.of(PRATERSTERN))
                .containsExactly(311, 345, 2100, 2704, 3450, 3886, 4112, 4117, 4260, 4263, 5707, 5710);
        assertThat(platforms.code(4112)).isNull();

        server.reset();
        clock.set("2026-10-24T20:47:55Z");
        platforms.refresh();

        assertThat(platforms.code(4112)).isEqualTo("2");
        assertThat(platforms.code(2100)).isNull();
    }

    private LiveProperties properties() {
        return new LiveProperties(
                server.monitorUrl(),
                server.platformsUrl(),
                server.platformCodesUrl(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                100,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5));
    }
}
