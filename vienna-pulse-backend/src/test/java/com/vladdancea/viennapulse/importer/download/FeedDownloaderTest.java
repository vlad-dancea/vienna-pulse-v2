package com.vladdancea.viennapulse.importer.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.vladdancea.viennapulse.importer.ImporterProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class FeedDownloaderTest {
    private static final byte[] FEED = "PK feed bytes".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private List<Path> feedsBefore;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // A slow answer must not block the server from noticing that the client hung up.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        feedsBefore = feedFiles();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsTheFeedIntoATemporaryFile() throws IOException {
        serve(exchange -> {
            exchange.sendResponseHeaders(200, FEED.length);
            try (var body = exchange.getResponseBody()) {
                body.write(FEED);
            }
        });

        try (var feed = downloader(RestClient.builder()).download()) {
            assertThat(Files.readAllBytes(feed.path())).isEqualTo(FEED);
        }
    }

    @Test
    void stopsAnOversizedDownloadAndDeletesItsFile() {
        serve(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[2 * 1024 * 1024]);
            }
        });

        var downloader = downloader(RestClient.builder());

        assertThatThrownBy(downloader::download)
                .isInstanceOf(RestClientException.class)
                .hasMessage("GTFS download passed 1 MB and was stopped");
        assertThat(feedFiles()).isEqualTo(feedsBefore);
    }

    @Test
    void stopsASlowDownloadAndDeletesItsFile() {
        // Each byte arrives well within the read timeout, but all of them would take ten seconds.
        serve(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                for (var i = 0; i < 100; i++) {
                    body.write(0);
                    body.flush();
                    Thread.sleep(100);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        // Only the read timeout bounds the whole download, as Spring Boot applies it to the client
        // it detects. Another HTTP client may apply it to each read instead and fail this test.
        var factory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withReadTimeout(Duration.ofSeconds(1)));
        var downloader = downloader(RestClient.builder().requestFactory(factory));

        assertThatThrownBy(downloader::download).isInstanceOf(ResourceAccessException.class);
        assertThat(feedFiles()).isEqualTo(feedsBefore);
    }

    private void serve(HttpHandler handler) {
        server.createContext("/gtfs.zip", handler);
    }

    private FeedDownloader downloader(RestClient.Builder clientBuilder) {
        var url = URI.create("http://localhost:" + server.getAddress().getPort() + "/gtfs.zip");
        return new FeedDownloader(new ImporterProperties(url, DataSize.ofMegabytes(1), false), clientBuilder);
    }

    // Other test runs on this machine share the directory, so this compares against the files
    // that were there when the test started.
    private static List<Path> feedFiles() {
        var feeds = new ArrayList<Path>();
        try (var files = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "pulse-gtfs-*.zip")) {
            files.forEach(feeds::add);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return feeds.stream().sorted().toList();
    }
}
