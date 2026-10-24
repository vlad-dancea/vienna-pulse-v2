package com.vladdancea.viennapulse.importer.download;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/// Serves a feed over HTTP on a free local port, standing in for wienerlinien.at.
public final class FeedServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicLong sent = new AtomicLong();
    private volatile HttpHandler answer = respond(404, text("Not found"));

    private FeedServer(HttpServer server) {
        this.server = server;
        server.createContext("/", exchange -> answer.handle(exchange));
        // A slow answer runs on a thread of its own, so it cannot hold up stopping the server.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public static FeedServer start() {
        try {
            return new FeedServer(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public URI uri() {
        var address = server.getAddress();
        try {
            return new URI(
                    "http", null, address.getAddress().getHostAddress(), address.getPort(), "/gtfs.zip", null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public void serve(byte[] feed) {
        answer = respond(200, feed);
    }

    public void fail(int status) {
        answer = respond(status, text("Something went wrong"));
    }

    /// Announces the whole feed but closes the connection after `sent` bytes, as a dropped
    /// connection would.
    public void cutOff(byte[] feed, int sent) {
        answer = respond(200, feed, sent);
    }

    /// Sends `bytes` bytes one at a time with `pause` in between, as a server that keeps sending
    /// slowly would.
    public void trickle(int bytes, Duration pause) {
        answer = exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                for (var i = 0; i < bytes; i++) {
                    exchange.getResponseBody().write(0);
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

    /// Sends until the client hangs up or `limit` bytes are out, counting them in [#sent()].
    public void flood(long limit) {
        answer = exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                var chunk = new byte[64 * 1024];
                while (sent.get() < limit) {
                    exchange.getResponseBody().write(chunk);
                    sent.addAndGet(chunk.length);
                }
            } catch (IOException exception) {
                // The client hung up.
            }
        };
    }

    public long sent() {
        return sent.get();
    }

    /// The downloaded feeds in the temporary directory, which the build gives the tests to
    /// themselves.
    public static List<Path> downloads() throws IOException {
        var directory = Path.of(System.getProperty("java.io.tmpdir"));
        var downloads = new ArrayList<Path>();
        try (var files = Files.newDirectoryStream(directory, "pulse-gtfs-*.zip")) {
            files.forEach(downloads::add);
        }
        return downloads;
    }

    private static HttpHandler respond(int status, byte[] body) {
        return respond(status, body, body.length);
    }

    private static HttpHandler respond(int status, byte[] body, int sent) {
        return exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body, 0, sent);
                // Sends what was written before closing finds the body short and drops the connection.
                exchange.getResponseBody().flush();
            }
        };
    }

    private static byte[] text(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
