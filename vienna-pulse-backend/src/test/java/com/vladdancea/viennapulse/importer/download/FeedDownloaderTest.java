package com.vladdancea.viennapulse.importer.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.importer.ImporterProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class FeedDownloaderTest {
    private static final byte[] FEED =
            "PK not really a zip, but the downloader never looks".getBytes(StandardCharsets.UTF_8);

    @AutoClose
    final FeedServer server = FeedServer.start();

    final FeedDownloader downloader = downloader(RestClient.builder());

    @BeforeEach
    void removeEarlierDownloads() throws IOException {
        for (var download : FeedServer.downloads()) {
            Files.delete(download);
        }
    }

    @Test
    void downloadsTheFeedIntoAFileThatClosingDeletes() throws IOException {
        server.serve(FEED);

        try (var feed = downloader.download()) {
            assertThat(feed.path()).hasBinaryContent(FEED);
            assertThat(FeedServer.downloads()).containsExactly(feed.path());
        }
        assertThat(FeedServer.downloads()).isEmpty();
    }

    @Test
    void failsOnAnErrorAnswerWithoutLeavingAFile() throws IOException {
        server.fail(500);

        assertThatThrownBy(downloader::download)
                .isInstanceOf(RestClientException.class)
                .hasMessage("GTFS download failed with HTTP 500");
        assertThat(FeedServer.downloads()).isEmpty();
    }

    @Test
    void deletesTheFileOfADownloadThatIsCutOff() throws IOException {
        server.cutOff(FEED, 10);

        assertThatThrownBy(downloader::download).isInstanceOf(RestClientException.class);
        assertThat(FeedServer.downloads()).isEmpty();
    }

    @Test
    void stopsAnOversizedDownloadWithoutLeavingAFile() throws IOException {
        server.flood(256 * 1024 * 1024);

        assertThatThrownBy(downloader::download)
                .isInstanceOf(RestClientException.class)
                .hasMessage("GTFS download passed 1 MB and was stopped");
        assertThat(FeedServer.downloads()).isEmpty();
        // Spring reads the rest of a response it closes, unless the downloader closed the body first.
        assertThat(server.sent()).isLessThan(64 * 1024 * 1024);
    }

    @Test
    void stopsASlowDownloadWithoutLeavingAFile() throws IOException {
        // Each byte arrives well within the read timeout, but all of them would take ten seconds.
        server.trickle(100, Duration.ofMillis(100));
        // Only the read timeout bounds the whole download, as Spring Boot applies it to the client
        // it detects. Another HTTP client may apply it to each read instead and fail this test.
        var factory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withReadTimeout(Duration.ofSeconds(1)));

        assertThatThrownBy(downloader(RestClient.builder().requestFactory(factory))::download)
                .isInstanceOf(ResourceAccessException.class);
        assertThat(FeedServer.downloads()).isEmpty();
    }

    @Test
    void deletesTheLeftoverFeedsAndNothingElse() throws IOException {
        var leftover = Files.createTempFile("pulse-gtfs-", ".zip");
        var other = Files.createTempFile("other-", ".zip");
        try {
            downloader.deleteLeftovers();

            assertThat(leftover).doesNotExist();
            assertThat(other).exists();
        } finally {
            Files.delete(other);
        }
    }

    private FeedDownloader downloader(RestClient.Builder clientBuilder) {
        return new FeedDownloader(
                new ImporterProperties(server.uri(), DataSize.ofMegabytes(1), false, List.of()), clientBuilder);
    }
}
