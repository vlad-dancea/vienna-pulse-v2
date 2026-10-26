package com.vladdancea.viennapulse.live;

import static java.util.Objects.requireNonNull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/// Stands in for Wiener Linien's realtime API on a free local port. It serves a list of the
/// platforms of Karlsplatz, Praterstern and Stephansplatz and of tram 10 at Hietzing and Wiener
/// Linien's codes for them, answers every `monitor` request with an answer that Wiener Linien sent
/// for Praterstern and Karlsplatz, cut to a few departures, and remembers the platforms each request
/// asked about.
public final class MonitorServer implements AutoCloseable {
    /// When Wiener Linien sent the answer, 21:47:55 on Saturday 2026-10-24 in Vienna.
    public static final String ANSWERED_AT = "2026-10-24T19:47:55Z";

    /// When Wiener Linien sent the answer for Hietzing, 12:39:44 on Monday 2026-10-26 in Vienna.
    public static final String HIETZING_ANSWERED_AT = "2026-10-26T11:39:44Z";

    private static final String JSON = "application/json";
    private static final byte[] PLATFORMS = resource("/live/haltepunkte.csv");
    private static final byte[] CODES = resource("/live/steige.csv");
    private static final byte[] ANSWER = resource("/live/monitor.json");
    private static final byte[] HIETZING = resource("/live/hietzing.json");

    private final HttpServer server;
    private final List<List<Integer>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger platformCodeRequests = new AtomicInteger();
    private volatile HttpHandler answer = respond(200, JSON, ANSWER);
    private volatile HttpHandler codes = respond(200, "text/csv", CODES);

    private MonitorServer(HttpServer server) {
        this.server = server;
        server.createContext("/haltepunkte.csv", respond(200, "text/csv", PLATFORMS));
        server.createContext("/steige.csv", exchange -> {
            platformCodeRequests.incrementAndGet();
            codes.handle(exchange);
        });
        server.createContext("/monitor", exchange -> {
            requests.add(platforms(exchange));
            answer.handle(exchange);
        });
        // A slow answer runs on a thread of its own, so it cannot hold up stopping the server.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public static MonitorServer start() {
        try {
            return new MonitorServer(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public URI monitorUrl() {
        return uri("/monitor");
    }

    public URI platformsUrl() {
        return uri("/haltepunkte.csv");
    }

    public URI platformCodesUrl() {
        return uri("/steige.csv");
    }

    /// Forgets the requests and answers the next ones with the recorded answer and the list of
    /// platform codes again.
    public void reset() {
        requests.clear();
        platformCodeRequests.set(0);
        answer = respond(200, JSON, ANSWER);
        codes = respond(200, "text/csv", CODES);
    }

    /// Answers the next requests with an answer that Wiener Linien sent for tram 10 at Hietzing, cut to
    /// two departures each way.
    public void answerForHietzing() {
        answer = respond(200, JSON, HIETZING);
    }

    /// Answers the next requests for the list of platform codes with a 404, as if Wiener Linien had
    /// removed it.
    public void removePlatformCodes() {
        codes = respond(404, "text/plain", new byte[0]);
    }

    /// Answers the next requests with `status` and `body`, as Wiener Linien do when they fail or
    /// refuse a request.
    public void fail(int status, String body) {
        answer = respond(status, JSON, body.getBytes(StandardCharsets.UTF_8));
    }

    /// Closes the connection of the next requests without an answer, as a server that drops them would.
    public void hangUp() {
        answer = HttpExchange::close;
    }

    /// Redirects the next requests to the same address, as a server that moved would.
    public void redirect() {
        answer = exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", monitorUrl().toString());
                exchange.sendResponseHeaders(302, -1);
            }
        };
    }

    /// Answers the next requests with `status` and then a space every `pause` until the client hangs
    /// up, as a server that never finishes would.
    public void trickle(int status, Duration pause) {
        answer = exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", JSON);
                exchange.sendResponseHeaders(status, 0);
                while (true) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(pause);
                }
            } catch (IOException exception) {
                // The client hung up.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /// Answers the next requests with the recorded answer after `delay`.
    public void answerAfter(Duration delay) {
        var recorded = respond(200, JSON, ANSWER);
        answer = exchange -> {
            try {
                Thread.sleep(delay);
                recorded.handle(exchange);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                // The client hung up.
            }
        };
    }

    /// How many requests asked for the list of platform codes.
    public int platformCodeRequests() {
        return platformCodeRequests.get();
    }

    /// The platforms each `monitor` request asked about, in order.
    public List<List<Integer>> requests() {
        return List.copyOf(requests);
    }

    /// The platforms each `monitor` request asked about, once `count` requests arrived or 5 seconds
    /// passed, for requests the backend sends on threads of its own.
    public List<List<Integer>> requests(int count) throws InterruptedException {
        var giveUp = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (requests.size() < count && System.nanoTime() < giveUp) {
            Thread.sleep(10);
        }
        return requests();
    }

    private static List<Integer> platforms(HttpExchange exchange) {
        var query = exchange.getRequestURI().getQuery();
        return Arrays.stream(query == null ? new String[0] : query.split("&"))
                .filter(parameter -> parameter.startsWith("stopId="))
                .map(parameter -> Integer.parseInt(parameter.substring("stopId=".length())))
                .toList();
    }

    private URI uri(String path) {
        var address = server.getAddress();
        return URI.create("http://" + address.getAddress().getHostAddress() + ":" + address.getPort() + path);
    }

    private static HttpHandler respond(int status, String type, byte[] body) {
        return exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        };
    }

    private static byte[] resource(String name) {
        try (InputStream stream = requireNonNull(MonitorServer.class.getResourceAsStream(name), name)) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
