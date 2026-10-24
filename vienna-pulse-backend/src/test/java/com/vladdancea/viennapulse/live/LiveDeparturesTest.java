package com.vladdancea.viennapulse.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class LiveDeparturesTest {
    // The platforms of each station in the order of the test's list of platforms.
    private static final List<Integer> KARLSPLATZ =
            List.of(1490, 1502, 1680, 1756, 2610, 2611, 4109, 4120, 4202, 4213, 4416, 4421, 5407, 5573, 7316);
    private static final List<Integer> PRATERSTERN =
            List.of(311, 345, 2100, 2704, 3450, 3886, 4112, 4117, 4260, 4263, 5707, 5710);
    private static final List<Integer> STEPHANSPLATZ = List.of(2720, 2903, 2915, 4111, 4118, 4906, 4911);

    // How Wiener Linien refuse a client over their limit.
    private static final String QUERY_LIMIT = """
            {"message": {"value": "Abfragelimit erreicht", "messageCode": 316,
                         "serverTime": "2026-10-24T21:47:55.000+0200"}}
            """;

    @AutoClose
    final MonitorServer server = MonitorServer.start();

    final TestClock clock = new TestClock();

    @BeforeEach
    void setTheClock() {
        clock.set(MonitorServer.ANSWERED_AT);
    }

    @Test
    void asksOnceForTheOpenStationsAndNotAtAllOnceTheyClose() {
        var live = live(100);
        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.PENDING);
        live.open("at:49:657", clock.instant());

        live.round();

        assertThat(server.requests()).containsExactly(concat(KARLSPLATZ, PRATERSTERN));
        assertThat(live.open("at:49:1040", clock.instant()))
                .isEqualTo(new Live(
                        LiveStatus.LIVE,
                        List.of(
                                departure(2100, "5B", "H", "21:49:00", "21:49:00"),
                                departure(2100, "5B", "H", "22:04:00", "22:04:00"),
                                departure(311, "O", "H", "21:42:00", "21:49:07"),
                                departure(311, "O", "H", "21:57:00", "21:57:40"),
                                departure(4112, "U1", "R", "21:49:30", "21:49:34"),
                                departure(4112, "U1", "R", "21:57:00", "21:58:25"))));
        // The third train has no planned time, as a train put in on top of the timetable.
        assertThat(live.open("at:49:657", clock.instant()))
                .isEqualTo(new Live(
                        LiveStatus.LIVE,
                        List.of(
                                departure(4421, "U4", "H", "21:51:30", "21:52:39"),
                                departure(4421, "U4", "H", "21:59:00", "21:58:05"))));

        clock.set("2026-10-24T19:48:56Z");
        live.round();

        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void leavesTheStationsThatDoNotFitForTheNextRound() {
        var live = live(20);
        Stream.of("at:49:1040", "at:49:1320", "at:49:657").forEach(stop -> live.open(stop, clock.instant()));

        live.round();
        clock.set("2026-10-24T19:48:10Z");
        live.round();
        clock.set("2026-10-24T19:48:25Z");
        live.round();

        assertThat(server.requests()).containsExactly(KARLSPLATZ, concat(PRATERSTERN, STEPHANSPLATZ), KARLSPLATZ);
    }

    // Rounds come every 15 seconds, but a station may wait longer for its turn when many are open.
    @Test
    void waitsForAnotherRoundOnceTheAnswerIsAMinuteOld() {
        var live = live(100);
        live.open("at:49:1040", clock.instant());
        live.round();

        clock.set("2026-10-24T19:48:54Z");
        assertThat(live.open("at:49:1040", clock.instant()).status()).isEqualTo(LiveStatus.LIVE);
        clock.set("2026-10-24T19:48:55Z");
        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.PENDING);
    }

    @Test
    void neverAsksAboutAStopWithoutAStation() {
        var live = live(100);

        // Outside Vienna, and missing from the list of platforms.
        assertThat(live.open("at:43:3121", clock.instant())).isEqualTo(Live.UNAVAILABLE);
        assertThat(live.open("at:49:1468", clock.instant())).isEqualTo(Live.UNAVAILABLE);
        live.round();

        assertThat(server.requests()).isEmpty();
    }

    // Wiener Linien refused with a 403, and their documentation lists the code for an error message.
    @ParameterizedTest
    @ValueSource(ints = {403, 200})
    void stopsAskingForFiveMinutesWhenRefused(int status) {
        var live = live(100);
        live.open("at:49:1040", clock.instant());
        live.round();
        server.fail(status, QUERY_LIMIT);
        clock.set("2026-10-24T19:48:10Z");

        live.round();
        server.reset();

        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.UNAVAILABLE);
        clock.set("2026-10-24T19:53:09Z");
        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.UNAVAILABLE);
        live.round();
        assertThat(server.requests()).isEmpty();

        // The answer from before the pause is too old to show.
        clock.set("2026-10-24T19:53:10Z");
        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.PENDING);
        live.round();
        assertThat(server.requests()).containsExactly(PRATERSTERN);
        assertThat(live.open("at:49:1040", clock.instant()).status()).isEqualTo(LiveStatus.LIVE);
    }

    // The status is enough to know that Wiener Linien refused, however slow the rest of the answer.
    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void pausesWhenARefusalIsTooSlowToRead(int status) {
        var live = live(100);
        server.trickle(status, Duration.ofMillis(100));
        live.open("at:49:1040", clock.instant());

        live.round();
        server.reset();

        clock.set("2026-10-24T19:48:10Z");
        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.UNAVAILABLE);
        live.round();
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void hasNoLiveTimesAfterAFailedRequestUntilTheNextAnswer() {
        var live = live(100);
        server.fail(500, "Internal Server Error");
        live.open("at:49:1040", clock.instant());

        live.round();

        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.UNAVAILABLE);
        server.reset();
        clock.set("2026-10-24T19:48:10Z");
        live.round();
        assertThat(live.open("at:49:1040", clock.instant()).status()).isEqualTo(LiveStatus.LIVE);
    }

    // A dropped connection must not make the HTTP client ask a second time.
    @Test
    void asksOnceWhenTheConnectionDropsWithoutAnAnswer() {
        var live = live(100);
        server.hangUp();
        live.open("at:49:1040", clock.instant());

        live.round();

        assertThat(server.requests()).containsExactly(PRATERSTERN);
        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.UNAVAILABLE);
    }

    // A redirect would be a second request in the same round.
    @Test
    void followsNoRedirect() {
        var live = live(100);
        server.redirect();
        live.open("at:49:1040", clock.instant());

        live.round();

        assertThat(server.requests()).containsExactly(PRATERSTERN);
        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.UNAVAILABLE);
    }

    // Every read gets data in time, but the answer never ends, and the rounds must go on.
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void givesUpOnAnAnswerThatNeverEnds() {
        var live = live(100);
        server.trickle(200, Duration.ofMillis(100));
        live.open("at:49:1040", clock.instant());

        live.round();

        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.UNAVAILABLE);
    }

    @Test
    void givesUpOnAnAnswerThatTakesLongerThanTheTimeout() {
        var live = live(100);
        server.answerAfter(Duration.ofSeconds(2));
        live.open("at:49:1040", clock.instant());

        live.round();

        assertThat(live.open("at:49:1040", clock.instant())).isEqualTo(Live.UNAVAILABLE);
    }

    // The production settings, but with a timeout short enough for a quick test.
    private LiveDepartures live(int maxPlatforms) {
        var properties = new LiveProperties(
                server.monitorUrl(),
                server.platformsUrl(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(60),
                maxPlatforms,
                Duration.ofMillis(500),
                Duration.ofMinutes(5));
        var platforms = new Platforms(properties, RestClient.builder(), clock);
        platforms.load();
        return new LiveDepartures(new MonitorClient(properties, RestClient.builder()), platforms, properties, clock);
    }

    // A departure on 2026-10-24, with its times in Vienna.
    private static LiveDeparture departure(int platform, String line, String direction, String planned, String real) {
        return new LiveDeparture(platform, line, direction, vienna(planned), vienna(real));
    }

    private static Instant vienna(String time) {
        return Instant.parse("2026-10-24T" + time + "+02:00");
    }

    private static List<Integer> concat(List<Integer> first, List<Integer> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
