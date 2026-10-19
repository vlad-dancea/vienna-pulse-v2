package com.vladdancea.viennapulse.importer;

import com.vladdancea.viennapulse.importer.download.FeedDownloader;
import com.vladdancea.viennapulse.importer.download.FeedValidator;
import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.route.Route;
import com.vladdancea.viennapulse.importer.read.route.RouteReader;
import com.vladdancea.viennapulse.importer.read.stop.Stop;
import com.vladdancea.viennapulse.importer.read.stop.StopReader;
import com.vladdancea.viennapulse.importer.store.Store;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/// Imports the GTFS feed on the `pulse.importer.cron` schedule: download, validate, then store it
/// in PostgreSQL. Each failure is logged once, and the downloaded feed is deleted when the import
/// ends, whatever the outcome. A new database gets its first import right after startup.
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportJob {
    private final FeedDownloader downloader;
    private final FeedValidator validator;
    private final RouteReader routeReader;
    private final StopReader stopReader;
    private final Store store;
    private final ImporterProperties properties;
    private final TaskScheduler scheduler;

    // Runs the import on the scheduler's single thread, like the daily one: startup and readiness
    // do not wait for the download, and the two imports can never overlap.
    @EventListener(ApplicationReadyEvent.class)
    void importWhenEmpty() {
        downloader.deleteLeftovers();
        if (properties.importWhenEmpty() && store.isEmpty()) {
            log.info("The timetable is empty, importing the feed now");
            // Nothing waits for the result: the scheduler logs whatever the import fails to handle.
            var _ = scheduler.schedule(this::run, Instant.now());
        }
    }

    @Scheduled(cron = "${pulse.importer.cron}", zone = "Europe/Vienna")
    public void run() {
        log.info("Feed import triggered");
        try (var feed = downloader.download()) {
            validator.validate(feed.path());
            replaceTimetable(feed.path());
        } catch (RestClientException exception) {
            log.error("Feed download failed", exception);
        } catch (IOException | FeedFormatException exception) {
            log.error("Feed import failed", exception);
        } catch (DataAccessException exception) {
            log.error("Storing the timetable failed", exception);
        }
    }

    private void replaceTimetable(Path feed) throws IOException {
        try (var zip = new ZipFile(feed.toFile())) {
            store.replace(staging -> {
                staging.copy("routes", Route.class, routeReader.read(zip).values()::forEach);
                staging.copy("stops", Stop.class, stopReader.read(zip).values()::forEach);
            });
        }
        log.info("Stored the timetable from {}", feed);
    }
}
