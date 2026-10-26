package com.vladdancea.viennapulse.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.vladdancea.viennapulse.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    // The live departures of the recorded answer, by station.
    private static final Live PRATERSTERN_LIVE = Live.live(List.of(
            departure(2100, "5B", "H", "Bhf. Heiligenstadt S U", "21:49:00", "21:49:00", 1),
            departure(2100, "5B", "H", "Bhf. Heiligenstadt S U", "22:04:00", "22:04:00", 16),
            departure(311, "O", "H", "Bruno-Marek-Allee", "21:42:00", "21:49:07", 1),
            departure(311, "O", "H", "Bruno-Marek-Allee", "21:57:00", "21:57:40", 9),
            departure(4112, "U1", "R", "Oberlaa", "21:49:30", "21:49:34", 1),
            departure(4112, "U1", "R", "Alaudagasse", "21:57:00", "21:58:25", 10)));
    // The third train has no planned time, as a train put in on top of the timetable.
    private static final Live KARLSPLATZ_LIVE = Live.live(List.of(
            departure(4421, "U4", "H", "Heiligenstadt", "21:51:30", "21:52:39", 4),
            departure(4421, "U4", "H", "Heiligenstadt", "21:59:00", "21:58:05", 10)));

    // How Wiener Linien refuse a client over their limit.
    private static final String QUERY_LIMIT = """
            {"message": {"value": "Abfragelimit erreicht", "messageCode": 316,
                         "serverTime": "2026-10-24T21:47:55.000+0200"}}
            """;

    // The backend starts a round interval before Wiener Linien sent the recorded answer, so it may
    // first ask then.
    private static final String STARTED = "2026-10-24T19:47:40Z";

    @AutoClose
    final MonitorServer server = MonitorServer.start();

    final TestClock clock = new TestClock();

    // The answers the rounds published.
    final List<Object> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setTheClock() {
        clock.set(STARTED);
    }

    @Test
    void asksOnceForTheOpenStationsAndNotAtAllOnceTheyClose() {
        var live = live(100);
        // The process before this one may have asked just before it stopped.
        assertThat(request(live, "at:49:1040")).isEqualTo(Live.pending(15));
        live.open("at:49:657", clock.instant());

        clock.set(MonitorServer.ANSWERED_AT);
        live.round();

        assertThat(server.requests()).containsExactly(concat(KARLSPLATZ, PRATERSTERN));
        assertThat(request(live, "at:49:1040")).isEqualTo(PRATERSTERN_LIVE);
        assertThat(request(live, "at:49:657")).isEqualTo(KARLSPLATZ_LIVE);

        clock.set("2026-10-24T19:48:56Z");
        live.round();

        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void leavesTheStationsThatDoNotFitForTheNextRound() {
        var live = live(20);
        Stream.of("at:49:1040", "at:49:1320", "at:49:657").forEach(stop -> live.open(stop, clock.instant()));

        clock.set(MonitorServer.ANSWERED_AT);
        live.round();
        clock.set("2026-10-24T19:48:10Z");
        live.round();
        clock.set("2026-10-24T19:48:25Z");
        live.round();

        assertThat(server.requests()).containsExactly(KARLSPLATZ, concat(PRATERSTERN, STEPHANSPLATZ), KARLSPLATZ);
    }

    @Test
    void asksAtOnceForAStationWithoutAnAnswer() throws InterruptedException {
        var live = live(100);
        clock.set(MonitorServer.ANSWERED_AT);

        live.open("at:49:1040", clock.instant());

        // The request goes out before the departures request has read the timetable.
        assertThat(server.requests(1)).containsExactly(PRATERSTERN);
        assertThat(live.await("at:49:1040", clock.instant(), List.of())).isEqualTo(PRATERSTERN_LIVE);
    }

    // The production check after a deploy: a second station opened soon after the first waits for
    // the next round, as long as the answer says.
    @Test
    void asksForAStationOpenedWithinTheIntervalInTheNextRound() {
        var live = live(100);
        clock.set(MonitorServer.ANSWERED_AT);
        assertThat(request(live, "at:49:1040")).isEqualTo(PRATERSTERN_LIVE);

        clock.set("2026-10-24T19:48:00.300Z");
        assertThat(request(live, "at:49:657")).isEqualTo(Live.pending(10));

        clock.set("2026-10-24T19:48:10Z");
        assertThat(request(live, "at:49:657")).isEqualTo(KARLSPLATZ_LIVE);
        assertThat(server.requests()).containsExactly(PRATERSTERN, concat(KARLSPLATZ, PRATERSTERN));
    }

    @Test
    void countsTheIntervalFromTheLastRequestWhicheverPathSentIt() {
        var live = live(100);
        clock.set(MonitorServer.ANSWERED_AT);
        request(live, "at:49:1040");

        clock.set("2026-10-24T19:48:09.999Z");
        live.round();
        assertThat(server.requests()).hasSize(1);

        clock.set("2026-10-24T19:48:10Z");
        live.round();
        assertThat(server.requests()).containsExactly(PRATERSTERN, PRATERSTERN);
    }

    // Wiener Linien take a second to answer the loop's round.
    @Test
    void countsTheIntervalFromTheEndOfTheLastRequest() throws InterruptedException {
        var live = live(100);
        server.answerAfter(Duration.ofMillis(200));
        live.open("at:49:1040", clock.instant());
        clock.set(MonitorServer.ANSWERED_AT);
        var loop = CompletableFuture.runAsync(live::round);
        assertThat(server.requests(1)).containsExactly(PRATERSTERN);
        clock.set("2026-10-24T19:47:56Z");
        loop.join();
        server.reset();

        clock.set("2026-10-24T19:48:10.999Z");
        assertThat(request(live, "at:49:657")).isEqualTo(Live.pending(1));
        clock.set("2026-10-24T19:48:11Z");
        assertThat(request(live, "at:49:657")).isEqualTo(KARLSPLATZ_LIVE);
        assertThat(server.requests()).containsExactly(concat(KARLSPLATZ, PRATERSTERN));
    }

    @Test
    void waitsForTheRunningRoundThatAsksAboutItsStation() throws InterruptedException {
        var live = live(100);
        server.answerAfter(Duration.ofMillis(200));
        live.open("at:49:1040", clock.instant());
        clock.set(MonitorServer.ANSWERED_AT);
        var loop = CompletableFuture.runAsync(live::round);
        assertThat(server.requests(1)).containsExactly(PRATERSTERN);

        assertThat(request(live, "at:49:657")).isEqualTo(Live.pending(15));
        assertThat(request(live, "at:49:1040")).isEqualTo(PRATERSTERN_LIVE);
        loop.join();
        assertThat(server.requests()).containsExactly(PRATERSTERN);
    }

    // Karlsplatz waits for the next round, which starts an interval after the running one ends, so
    // its answer waits for that end too.
    @Test
    void countsTheSecondsToTheNextRoundFromTheEndOfTheRunningOne() throws InterruptedException {
        var live = live(100);
        server.answerAfter(Duration.ofMillis(300));
        clock.set(MonitorServer.ANSWERED_AT);
        live.open("at:49:1040", clock.instant());
        assertThat(server.requests(1)).containsExactly(PRATERSTERN);

        var started = System.nanoTime();
        assertThat(request(live, "at:49:657")).isEqualTo(Live.pending(15));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(200));
    }

    // Departures requests for three stations and the loop find the gate open at the same moment, many
    // times over, because a race shows only now and then.
    @Test
    void asksOnceWhenRequestsAndTheLoopFindTheGateOpenTogether() throws Exception {
        var platforms = Map.of("at:49:1040", PRATERSTERN, "at:49:657", KARLSPLATZ, "at:49:1320", STEPHANSPLATZ);
        var stops = List.copyOf(platforms.keySet());
        try (var threads = Executors.newFixedThreadPool(16)) {
            for (var attempt = 0; attempt < 20; attempt++) {
                clock.set(STARTED);
                var live = live(100);
                clock.set(MonitorServer.ANSWERED_AT);
                server.reset();
                var together = new CyclicBarrier(16);
                var requests = new ArrayList<Future<Live>>();
                var loops = new ArrayList<Future<?>>();
                for (var i = 0; i < 12; i++) {
                    var stop = stops.get(i % stops.size());
                    requests.add(threads.submit(() -> {
                        together.await();
                        return request(live, stop);
                    }));
                }
                for (var i = 0; i < 4; i++) {
                    loops.add(threads.submit(() -> {
                        together.await();
                        live.round();
                        return null;
                    }));
                }
                for (var loop : loops) {
                    loop.get();
                }
                var answers = new ArrayList<Live>();
                for (var request : requests) {
                    answers.add(request.get());
                }

                // Each request gets its live times if its station was open in time for the round, and
                // waits for the next one otherwise.
                var asked = server.requests().getFirst();
                for (var i = 0; i < answers.size(); i++) {
                    var answer = answers.get(i);
                    if (asked.containsAll(platforms.get(stops.get(i % stops.size())))) {
                        assertThat(answer.status()).isEqualTo(LiveStatus.LIVE);
                    } else {
                        assertThat(answer).isEqualTo(Live.pending(15));
                    }
                }
                assertThat(server.requests()).hasSize(1);
            }
        }
    }

    // A departures request read two of the answer's departures from the timetable while the gate was
    // shut, and the round pairs the answer with them.
    @Test
    void publishesEachAnswerPairedWithTheTimetableTheLastRequestRead() {
        var live = live(100);
        var tram = new PlannedDeparture("o-late", "o", "O", "Wien Bruno-Marek-Allee", 0, vienna("21:41:00"));
        var subway = new PlannedDeparture("u1-oberlaa", "u1", "U1", "Wien Oberlaa", 1, vienna("21:49:00"));
        live.open("at:49:1040", clock.instant());
        assertThat(live.await("at:49:1040", clock.instant(), List.of(tram, subway)))
                .isEqualTo(Live.pending(15));

        clock.set(MonitorServer.ANSWERED_AT);
        live.round();

        var praterstern = 60_201_040;
        var answer = PRATERSTERN_LIVE.departures();
        assertThat(events)
                .containsExactly(new RoundAnswered(
                        Instant.parse(MonitorServer.ANSWERED_AT),
                        List.of(
                                new RoundAnswered.Seen(praterstern, answer.get(0), null),
                                new RoundAnswered.Seen(praterstern, answer.get(1), null),
                                new RoundAnswered.Seen(praterstern, answer.get(2), tram),
                                new RoundAnswered.Seen(praterstern, answer.get(3), null),
                                new RoundAnswered.Seen(praterstern, answer.get(4), subway),
                                new RoundAnswered.Seen(praterstern, answer.get(5), null))));
    }

    // Rounds come every 15 seconds, but a station may wait longer for its turn when many are open.
    @Test
    void asksAgainOnceTheAnswerIsAMinuteOld() {
        var live = live(100);
        clock.set(MonitorServer.ANSWERED_AT);
        request(live, "at:49:1040");

        clock.set("2026-10-24T19:48:54Z");
        assertThat(request(live, "at:49:1040")).isEqualTo(PRATERSTERN_LIVE);
        assertThat(server.requests()).hasSize(1);
        clock.set("2026-10-24T19:48:55Z");
        assertThat(request(live, "at:49:1040")).isEqualTo(PRATERSTERN_LIVE);
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    void neverAsksAboutAStopWithoutAStation() {
        var live = live(100);
        clock.set(MonitorServer.ANSWERED_AT);

        // Outside Vienna, and missing from the list of platforms.
        assertThat(request(live, "at:43:3121")).isEqualTo(Live.UNAVAILABLE);
        assertThat(request(live, "at:49:1468")).isEqualTo(Live.UNAVAILABLE);
        live.round();

        assertThat(server.requests()).isEmpty();
    }

    // Wiener Linien refused with a 403, and their documentation lists the code for an error message.
    @ParameterizedTest
    @ValueSource(ints = {403, 200})
    void stopsAskingForFiveMinutesWhenRefused(int status) {
        var live = live(100);
        clock.set(MonitorServer.ANSWERED_AT);
        request(live, "at:49:1040");
        server.fail(status, QUERY_LIMIT);
        clock.set("2026-10-24T19:48:10Z");

        live.round();
        server.reset();

        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);
        clock.set("2026-10-24T19:53:09Z");
        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);
        live.round();
        assertThat(server.requests()).isEmpty();

        // The answer from before the pause is too old to show, so the first request after it asks.
        clock.set("2026-10-24T19:53:10Z");
        assertThat(request(live, "at:49:1040")).isEqualTo(PRATERSTERN_LIVE);
        assertThat(server.requests()).containsExactly(PRATERSTERN);
    }

    // The status is enough to know that Wiener Linien refused, however slow the rest of the answer.
    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void pausesWhenARefusalIsTooSlowToRead(int status) {
        var live = live(100);
        server.trickle(status, Duration.ofMillis(100));
        live.open("at:49:1040", clock.instant());
        clock.set(MonitorServer.ANSWERED_AT);

        live.round();
        server.reset();

        clock.set("2026-10-24T19:48:10Z");
        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);
        live.round();
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void hasNoLiveTimesAfterAFailedRequestUntilTheNextAnswer() {
        var live = live(100);
        server.fail(500, "Internal Server Error");
        clock.set(MonitorServer.ANSWERED_AT);

        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);

        server.reset();
        clock.set("2026-10-24T19:48:10Z");
        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);
        live.round();
        assertThat(request(live, "at:49:1040")).isEqualTo(PRATERSTERN_LIVE);
    }

    // A dropped connection must not make the HTTP client ask a second time.
    @Test
    void asksOnceWhenTheConnectionDropsWithoutAnAnswer() {
        var live = live(100);
        server.hangUp();
        clock.set(MonitorServer.ANSWERED_AT);

        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);
        assertThat(server.requests()).containsExactly(PRATERSTERN);
    }

    // A redirect would be a second request in the same round.
    @Test
    void followsNoRedirect() {
        var live = live(100);
        server.redirect();
        clock.set(MonitorServer.ANSWERED_AT);

        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);
        assertThat(server.requests()).containsExactly(PRATERSTERN);
    }

    // Every read gets data in time, but the answer never ends, and the rounds must go on.
    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void givesUpOnAnAnswerThatNeverEnds() {
        var live = live(100);
        server.trickle(200, Duration.ofMillis(100));
        live.open("at:49:1040", clock.instant());
        clock.set(MonitorServer.ANSWERED_AT);

        live.round();

        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);
    }

    // Wiener Linien answer after 2 seconds, which the departures request must not wait for.
    @Test
    void givesUpOnAnAnswerThatTakesLongerThanTheTimeout() {
        var live = live(100);
        server.answerAfter(Duration.ofSeconds(2));
        clock.set(MonitorServer.ANSWERED_AT);

        var started = System.nanoTime();
        assertThat(request(live, "at:49:1040")).isEqualTo(Live.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
    }

    // The timetable took as long as the round may, so the departures request answers at once,
    // although Wiener Linien are still busy.
    @Test
    void waitsNoLongerThanTheTimeoutAfterTheRoundStarted() {
        var live = live(100);
        server.answerAfter(Duration.ofSeconds(2));
        clock.set(MonitorServer.ANSWERED_AT);
        live.open("at:49:1040", clock.instant());

        clock.set("2026-10-24T19:47:55.500Z");
        var started = System.nanoTime();
        assertThat(live.await("at:49:1040", clock.instant(), List.of())).isEqualTo(Live.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
    }

    // The production settings, but with a timeout short enough for a quick test. The backend starts
    // at the clock's time.
    private LiveDepartures live(int maxPlatforms) {
        var properties = new LiveProperties(
                server.monitorUrl(),
                server.platformsUrl(),
                server.platformCodesUrl(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                maxPlatforms,
                Duration.ofMillis(500),
                Duration.ofMinutes(5));
        var platforms = new Platforms(properties, RestClient.builder(), clock);
        platforms.load();
        return new LiveDepartures(
                new MonitorClient(properties, RestClient.builder()), platforms, properties, clock, clock, events::add);
    }

    // What a departures request for the stop gets now, when the timetable has no departures and takes
    // no time to read.
    private Live request(LiveDepartures live, String stopId) {
        live.open(stopId, clock.instant());
        return live.await(stopId, clock.instant(), List.of());
    }

    // A departure on 2026-10-24, with its times in Vienna.
    private static LiveDeparture departure(
            int platform, String line, String direction, String towards, String planned, String real, int countdown) {
        return new LiveDeparture(platform, line, direction, towards, vienna(planned), vienna(real), countdown);
    }

    private static Instant vienna(String time) {
        return Instant.parse("2026-10-24T" + time + "+02:00");
    }

    private static List<Integer> concat(List<Integer> first, List<Integer> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
