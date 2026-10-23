package com.vladdancea.viennapulse.importer.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.importer.ImporterProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class FeedDownloaderTest {
    private static final byte[] FEED =
            "PK not really a zip, but the downloader never looks".getBytes(StandardCharsets.UTF_8);

    @AutoClose
    final FeedServer server = FeedServer.start();

    final FeedDownloader downloader =
            new FeedDownloader(new ImporterProperties(server.uri(), false), RestClient.builder());

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
}
