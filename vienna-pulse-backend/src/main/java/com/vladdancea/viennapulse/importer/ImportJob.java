package com.vladdancea.viennapulse.importer;

import com.vladdancea.viennapulse.importer.ingestor.FeedFormatException;
import com.vladdancea.viennapulse.importer.ingestor.RouteReader;
import com.vladdancea.viennapulse.importer.zip.FeedDownloader;
import com.vladdancea.viennapulse.importer.zip.FeedValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
@Slf4j
@RequiredArgsConstructor
public class ImportJob {
    private final FeedDownloader downloader;
    private final FeedValidator validator;
    private final RouteReader routeReader;

    @Scheduled(cron = "${pulse.importer.cron}", zone = "Europe/Vienna")
    public void run() {
        log.info("Feed import triggered");
        try {
            var feed = downloader.download();
            validate(feed);
            ingest(feed);
        } catch (RestClientException exception) {
            log.error("Feed download failed", exception);
        } catch (IOException | FeedFormatException exception) {
            log.error("Feed ingestion failed", exception);
        }
    }

    private void validate(Path feed) throws IOException {
        try {
            validator.validate(feed);
        } catch (IOException exception) {
            discard(feed, exception);
            throw exception;
        }
    }

    private void ingest(Path feed) throws IOException {
        try (var zip = new ZipFile(feed.toFile())) {
            var routes = routeReader.read(zip);
            log.info("Read {} routes from {}", routes.size(), feed);
        } catch (IOException | FeedFormatException exception) {
            discard(feed, exception);
            throw exception;
        }
    }

    private static void discard(Path feed, Exception reason) {
        try {
            Files.deleteIfExists(feed);
        } catch (IOException cleanupException) {
            reason.addSuppressed(cleanupException);
        }
    }
}
