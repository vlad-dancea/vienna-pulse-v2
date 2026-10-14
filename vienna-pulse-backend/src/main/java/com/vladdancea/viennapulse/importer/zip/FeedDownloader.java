package com.vladdancea.viennapulse.importer.zip;

import com.vladdancea.viennapulse.importer.ImporterProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final ImporterProperties properties;
    private final RestClient.Builder clientBuilder;

    /// Downloads the feed and returns the file, which the caller must delete when done. Throws
    /// [RestClientException] if the request fails or the response is not 2xx.
    public Path download() {
        log.info("Starting download process from {}", properties.feedUrl());
        return clientBuilder.build().get().uri(properties.feedUrl()).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RestClientException("GTFS download failed with HTTP "
                        + response.getStatusCode().value());
            }

            var file = Files.createTempFile("pulse-gtfs-", ".zip");
            try {
                var bytes = Files.copy(response.getBody(), file, StandardCopyOption.REPLACE_EXISTING);
                log.info("Downloaded {} bytes to {}", bytes, file);
                return file;
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
}
