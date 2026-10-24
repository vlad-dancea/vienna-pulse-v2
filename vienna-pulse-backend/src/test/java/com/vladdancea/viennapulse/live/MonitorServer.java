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

/// Stands in for Wiener Linien's realtime API on a free local port. It serves a list of the
/// platforms of Karlsplatz, Praterstern and Stephansplatz, answers every `monitor` request with an
/// answer that Wiener Linien sent for Praterstern and Karlsplatz, cut to a few departures, and
/// remembers the platforms each request asked about.
public final class MonitorServer implements AutoCloseable {
    /// When Wiener Linien sent the answer, 21:47:55 on Saturday 2026-10-24 in Vienna.
    public static final String ANSWERED_AT = "2026-10-24T19:47:55Z";

    private static final String JSON = "application/json";
    private static final byte[] PLATFORMS = resource("/live/haltepunkte.csv");
    private static final byte[] ANSWER = resource("/live/monitor.json");

    private final HttpServer server;
    private final List<List<Integer>> requests = new CopyOnWriteArrayList<>();
    private volatile HttpHandler answer = respond(200, JSON, ANSWER);

    private MonitorServer(HttpServer server) {
        this.server = server;
        server.createContext("/haltepunkte.csv", respond(200, "text/csv", PLATFORMS));
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

    /// Forgets the requests and answers the next ones with the recorded answer again.
    public void reset() {
        requests.clear();
        answer = respond(200, JSON, ANSWER);
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

    /// The platforms each `monitor` request asked about, in order.
    public List<List<Integer>> requests() {
        return List.copyOf(requests);
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
