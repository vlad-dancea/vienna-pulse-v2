package com.vladdancea.viennapulse.importer;

import com.vladdancea.viennapulse.importer.download.DownloadedFeed;
import com.vladdancea.viennapulse.importer.download.FeedDownloader;
import com.vladdancea.viennapulse.importer.download.FeedValidator;
import com.vladdancea.viennapulse.importer.history.FeedFile;
import com.vladdancea.viennapulse.importer.history.ImportAttempt;
import com.vladdancea.viennapulse.importer.history.ImportHistory;
import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.calendar.CalendarDate;
import com.vladdancea.viennapulse.importer.read.calendar.CalendarDateReader;
import com.vladdancea.viennapulse.importer.read.calendar.ServiceCalendar;
import com.vladdancea.viennapulse.importer.read.calendar.ServiceCalendarReader;
import com.vladdancea.viennapulse.importer.read.route.Route;
import com.vladdancea.viennapulse.importer.read.route.RouteReader;
import com.vladdancea.viennapulse.importer.read.shape.ShapePoint;
import com.vladdancea.viennapulse.importer.read.shape.ShapeReader;
import com.vladdancea.viennapulse.importer.read.stop.Stop;
import com.vladdancea.viennapulse.importer.read.stop.StopReader;
import com.vladdancea.viennapulse.importer.read.stoptime.StopTimeReader;
import com.vladdancea.viennapulse.importer.read.trip.Trip;
import com.vladdancea.viennapulse.importer.read.trip.TripReader;
import com.vladdancea.viennapulse.importer.store.Store;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
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
/// in PostgreSQL. Each attempt is recorded in the [ImportHistory], each failure is logged once, and
/// the downloaded feed is deleted when the import ends, whatever the outcome. A new database gets its
/// first import right after startup.
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportJob {
    private final FeedDownloader downloader;
    private final FeedValidator validator;
    private final RouteReader routeReader;
    private final StopReader stopReader;
    private final ShapeReader shapeReader;
    private final TripReader tripReader;
    private final StopTimeReader stopTimeReader;
    private final ServiceCalendarReader serviceCalendarReader;
    private final CalendarDateReader calendarDateReader;
    private final Store store;
    private final ImportHistory history;
    private final ImporterProperties properties;
    private final TaskScheduler scheduler;
    private final Clock clock;

    // Runs the import on the scheduler's single thread, like the daily one: startup and readiness
    // do not wait for the download, and the two imports can never overlap.
    @EventListener(ApplicationReadyEvent.class)
    void importWhenEmpty() {
        downloader.deleteLeftovers();
        if (properties.importWhenEmpty() && store.needsImport()) {
            log.info("The timetable is empty or incomplete, importing the feed now");
            // Nothing waits for the result: the scheduler logs whatever the import fails to handle.
            var _ = scheduler.schedule(this::run, Instant.now());
        }
    }

    @Scheduled(cron = "${pulse.importer.cron}", zone = "Europe/Vienna")
    public void run() {
        log.info("Feed import triggered");
        var attempt = new ImportAttempt(clock.instant(), properties.feedUrl());
        try {
            var feed = downloader.download();
            try {
                attempt = attempt.withFeed(FeedFile.of(feed.path()));
                validator.validate(feed.path());
                replaceTimetable(feed.path(), attempt);
            } finally {
                delete(feed);
            }
        } catch (RestClientException exception) {
            log.error("Feed download failed", exception);
            history.failed(attempt, exception);
        } catch (IOException | FeedFormatException exception) {
            log.error("Feed import failed", exception);
            history.failed(attempt, exception);
        } catch (DataAccessException exception) {
            log.error("Storing the timetable failed", exception);
            history.failed(attempt, exception);
        } catch (RuntimeException exception) {
            // Such as a table the import left empty. Caught here only so the history records it too.
            log.error("Feed import failed unexpectedly", exception);
            history.failed(attempt, exception);
        }
    }

    // A feed left behind only takes disk space until the next start deletes it, so it does not fail an
    // import that may already have replaced the timetable.
    private static void delete(DownloadedFeed feed) {
        try {
            feed.close();
        } catch (IOException exception) {
            log.warn("Could not delete the downloaded feed {}", feed.path(), exception);
        }
    }

    private void replaceTimetable(Path feed, ImportAttempt attempt) throws IOException {
        try (var zip = new ZipFile(feed.toFile())) {
            store.replace(
                    staging -> {
                        staging.copy(
                                "routes", Route.class, routeReader.read(zip).values()::forEach);
                        staging.copy("stops", Stop.class, stopReader.read(zip).values()::forEach);
                        staging.copy("shape_points", ShapePoint.class, points -> shapeReader.read(zip, points));
                        staging.copy("trips", Trip.class, trips -> tripReader.read(zip, trips));
                        staging.stopTimes(stopTimes -> stopTimeReader.read(zip, stopTimes));
                        staging.copy(
                                "service_calendars",
                                ServiceCalendar.class,
                                serviceCalendarReader.read(zip).values()::forEach);
                        staging.copy(
                                "calendar_dates",
                                CalendarDate.class,
                                calendarDateReader.read(zip).values()::forEach);
                    },
                    rowCounts -> history.succeeded(attempt, rowCounts));
        }
        log.info("Stored the timetable from {}", feed);
    }
}
