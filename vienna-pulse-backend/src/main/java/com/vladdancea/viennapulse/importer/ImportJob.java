package com.vladdancea.viennapulse.importer;

import com.vladdancea.viennapulse.importer.ingestor.FeedFormatException;
import com.vladdancea.viennapulse.importer.ingestor.route.RouteReader;
import com.vladdancea.viennapulse.importer.ingestor.stop.StopReader;
import com.vladdancea.viennapulse.importer.zip.FeedDownloader;
import com.vladdancea.viennapulse.importer.zip.FeedValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/// Imports the GTFS feed on the `pulse.importer.cron` schedule:
/// download, validate, then read. Each failure is logged once, and the downloaded feed is deleted
/// when the import ends, whatever the outcome.
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportJob {
    private final FeedDownloader downloader;
    private final FeedValidator validator;
    private final RouteReader routeReader;
    private final StopReader stopReader;

    @Scheduled(cron = "${pulse.importer.cron}", zone = "Europe/Vienna")
    public void run() {
        log.info("Feed import triggered");
        try (var feed = downloader.download()) {
            validator.validate(feed.path());
            ingest(feed.path());
        } catch (RestClientException exception) {
            log.error("Feed download failed", exception);
        } catch (IOException | FeedFormatException exception) {
            log.error("Feed ingestion failed", exception);
        }
    }

    private void ingest(Path feed) throws IOException {
        try (var zip = new ZipFile(feed.toFile())) {
            var routes = routeReader.read(zip);
            log.info("Read {} routes from {}", routes.size(), feed);
            var stops = stopReader.read(zip);
            log.info("Read {} stops from {}", stops.size(), feed);
        }
    }
}
