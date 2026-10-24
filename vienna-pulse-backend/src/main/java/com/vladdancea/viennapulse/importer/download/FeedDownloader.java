package com.vladdancea.viennapulse.importer.download;

import com.vladdancea.viennapulse.importer.ImporterProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/// Streams the GTFS feed into a temporary ZIP file, so the whole feed is never held
/// in memory.
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedDownloader {
    private static final String PREFIX = "pulse-gtfs-";
    private static final String SUFFIX = ".zip";

    private final ImporterProperties properties;
    private final RestClient.Builder clientBuilder;

    /// Downloads the feed into a temporary file, which closing the returned feed deletes. Throws
    /// [RestClientException] if the request fails, the response is not 2xx or the feed is larger
    /// than `pulse.importer.max-download-size`.
    public DownloadedFeed download() {
        log.info("Starting download process from {}", properties.feedUrl());
        return clientBuilder.build().get().uri(properties.feedUrl()).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RestClientException("GTFS download failed with HTTP "
                        + response.getStatusCode().value());
            }

            var file = Files.createTempFile(PREFIX, SUFFIX);
            try {
                var bytes = copy(response.getBody(), file);
                log.info("Downloaded {} bytes to {}", bytes, file);
                return new DownloadedFeed(file);
            } catch (IOException | RuntimeException exception) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
                throw exception;
            }
        });
    }

    private long copy(InputStream body, Path file) throws IOException {
        var maxBytes = properties.maxDownloadSize().toBytes();
        var buffer = new byte[64 * 1024];
        var bytes = 0L;
        // Closing the body stops the transfer. Spring would otherwise read the rest of an oversized
        // feed to reuse the connection.
        try (body;
                var out = Files.newOutputStream(file)) {
            for (int read; (read = body.read(buffer)) != -1; ) {
                bytes += read;
                if (bytes > maxBytes) {
                    throw new RestClientException("GTFS download passed "
                            + properties.maxDownloadSize().toMegabytes() + " MB and was stopped");
                }
                out.write(buffer, 0, read);
            }
        }
        return bytes;
    }

    /// Deletes the feeds that imports killed mid-way, by a crash or a redeploy, left in the
    /// temporary directory. Each is about 100 MB, and nothing else would ever delete them.
    public void deleteLeftovers() {
        var directory = Path.of(System.getProperty("java.io.tmpdir"));
        try (var leftovers = Files.newDirectoryStream(directory, PREFIX + "*" + SUFFIX)) {
            for (var leftover : leftovers) {
                Files.deleteIfExists(leftover);
                log.info("Deleted the leftover feed {}", leftover);
            }
        } catch (IOException exception) {
            log.warn("Could not delete the leftover feeds in {}", directory, exception);
        }
    }
}
