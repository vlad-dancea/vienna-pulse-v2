package com.vladdancea.viennapulse.live;

import static java.util.Objects.requireNonNullElse;

import java.io.Serial;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/// Asks Wiener Linien's `monitor` endpoint for the departures from a list of platforms.
@Component
public class MonitorClient {
    // Names the site, so that Wiener Linien know whom to contact about its requests.
    private static final String USER_AGENT = "Vienna Pulse (https://pulse.vladdancea.com)";
    // The answer's code when Wiener Linien refuse a client over the limit, "Abfragelimit erreicht".
    private static final int QUERY_LIMIT = 316;
    // Like 2026-10-24T21:33:00.000+0200, whose offset lacks the colon Java's ISO formats expect.
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendOffset("+HHMM", "Z")
            .toFormatter(Locale.ROOT);

    private final LiveProperties properties;
    private final RestClient client;

    public MonitorClient(LiveProperties properties, RestClient.Builder clientBuilder) {
        this.properties = properties;
        // Apache HttpClient without its retries, and following no redirect, since either would make a
        // second request in the same round. The JDK's client sends a GET again by itself when the
        // connection drops before an answer.
        var requests = ClientHttpRequestFactoryBuilder.httpComponents()
                .withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries)
                .build(HttpClientSettings.defaults()
                        .withRedirects(HttpRedirects.DONT_FOLLOW)
                        .withConnectTimeout(properties.timeout())
                        .withReadTimeout(properties.timeout()));
        this.client = clientBuilder
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    /// The departures of the next 70 minutes from the platforms with the numbers `platforms`. Throws
    /// [Blocked] when Wiener Linien refuse to answer over their fair-use rules, and another runtime
    /// exception when the request fails otherwise or takes longer than `pulse.live.timeout`.
    List<LiveDeparture> departures(List<Integer> platforms) {
        var uri = UriComponentsBuilder.fromUri(properties.monitorUrl())
                .queryParam("stopId", platforms.toArray())
                .build()
                .toUri();
        // Apache's timeouts bound connecting and each wait for data, but not an answer that never
        // ends. Interrupting a virtual thread closes its socket, which ends the request.
        var refusal = new AtomicReference<@Nullable Blocked>();
        var request = new FutureTask<>(() -> fetch(uri, refusal));
        Thread.ofVirtual().name("monitor-request").start(request);
        try {
            return request.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            // Closing the response reads the rest of its body, so a refusal can run out of time too.
            var blocked = refusal.get();
            if (blocked != null) {
                throw blocked;
            }
            throw new ResourceAccessException("Wiener Linien did not answer within " + properties.timeout());
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new RestClientException("The request to Wiener Linien failed", exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResourceAccessException("Interrupted while waiting for Wiener Linien");
        } finally {
            request.cancel(true);
        }
    }

    // Sets `refusal` as soon as the status shows that Wiener Linien refused the request.
    private List<LiveDeparture> fetch(URI uri, AtomicReference<@Nullable Blocked> refusal) {
        var answer = client.get().uri(uri).accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
            var status = response.getStatusCode().value();
            // A 403 is how Wiener Linien block a client, and a 429 is the usual way to ask for fewer requests.
            if (status == 403 || status == 429) {
                var blocked = new Blocked("Wiener Linien answered with HTTP " + status);
                refusal.set(blocked);
                throw blocked;
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RestClientException("Wiener Linien answered with HTTP " + status);
            }
            return response.bodyTo(Answer.class);
        });
        if (answer == null) {
            throw new RestClientException("Wiener Linien answered without a body");
        }
        if (answer.message().messageCode() == QUERY_LIMIT) {
            throw new Blocked("Wiener Linien answered with code " + QUERY_LIMIT);
        }
        var data = answer.data();
        if (data == null) {
            throw new RestClientException("Wiener Linien answered without data: code %d, %s"
                    .formatted(answer.message().messageCode(), answer.message().value()));
        }
        return departures(data);
    }

    private static List<LiveDeparture> departures(Data data) {
        var departures = new ArrayList<LiveDeparture>();
        for (var monitor : data.monitors()) {
            var platform = monitor.locationStop().properties().attributes().rbl();
            for (var line : requireNonNullElse(monitor.lines(), List.<Line>of())) {
                for (var departure : requireNonNullElse(line.departures().departure(), List.<Departure>of())) {
                    var planned = departure.departureTime().timePlanned();
                    // A train put in on top of the timetable has no planned time to pair it with.
                    if (planned == null) {
                        continue;
                    }
                    var real = departure.departureTime().timeReal();
                    departures.add(new LiveDeparture(
                            platform,
                            line.name(),
                            line.direction(),
                            instant(planned),
                            real == null ? null : instant(real)));
                }
            }
        }
        return departures;
    }

    private static Instant instant(String time) {
        return OffsetDateTime.parse(time, TIME).toInstant();
    }

    /// Thrown when Wiener Linien refuse a request over their fair-use rules.
    static final class Blocked extends RestClientException {
        @Serial
        private static final long serialVersionUID = 1L;

        Blocked(String message) {
            super(message);
        }
    }

    // The parts of an answer the board reads. Wiener Linien leave out `lines` and `departure` where
    // there are none, and leave `timePlanned` empty for a train put in on top of the timetable.
    record Answer(@Nullable Data data, Message message) {}

    record Message(String value, int messageCode) {}

    record Data(List<Monitor> monitors) {}

    record Monitor(LocationStop locationStop, @Nullable List<Line> lines) {}

    record LocationStop(Properties properties) {}

    record Properties(Attributes attributes) {}

    // `rbl` is the platform's number, which requests call `stopId`.
    record Attributes(int rbl) {}

    record Line(String name, String direction, Departures departures) {}

    record Departures(@Nullable List<Departure> departure) {}

    record Departure(DepartureTime departureTime) {}

    record DepartureTime(
            @Nullable String timePlanned, @Nullable String timeReal) {}
}
