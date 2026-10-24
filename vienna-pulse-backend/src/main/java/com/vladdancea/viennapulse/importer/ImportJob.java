package com.vladdancea.viennapulse.importer;

import com.vladdancea.viennapulse.importer.download.FeedDownloader;
import com.vladdancea.viennapulse.importer.download.FeedValidator;
import com.vladdancea.viennapulse.importer.history.FeedFile;
import com.vladdancea.viennapulse.importer.history.ImportAttempt;
import com.vladdancea.viennapulse.importer.history.ImportHistory;
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
import java.util.concurrent.ScheduledFuture;
import java.util.zip.ZipFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/// Downloads, validates and stores the GTFS feed on the `pulse.importer.cron` schedule, and right
/// after startup into a new database, recording each attempt in the [ImportHistory]. A failed
/// import is retried after each of the `pulse.importer.retry-delays`.
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

    // Only the scheduler's single thread runs imports and changes this.
    private @Nullable ScheduledFuture<?> retry;

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
        // A scheduled run starts its own retries, so the retries of an earlier run end here.
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
        attempt(0);
    }

    // Every failure is retried: the feed may come back fixed within hours, and a retry costs one
    // download.
    private void attempt(int retries) {
        if (importFeed()) {
            return;
        }
        var delays = properties.retryDelays();
        if (retries == delays.size()) {
            log.error("Feed import failed {} times, waiting for the next scheduled run", retries + 1);
            return;
        }
        var delay = delays.get(retries);
        log.warn("Retrying the feed import in {}", delay);
        retry = scheduler.schedule(
                () -> attempt(retries + 1), scheduler.getClock().instant().plus(delay));
    }

    private boolean importFeed() {
        log.info("Feed import triggered");
        var attempt = new ImportAttempt(clock.instant(), properties.feedUrl());
        try {
            var feed = downloader.download();
            try {
                attempt = attempt.withFeed(FeedFile.of(feed.path()));
                validator.validate(feed.path());
                replaceTimetable(feed.path(), attempt);
                return true;
            } finally {
                cleanUp(feed, "the downloaded feed " + feed.path());
            }
        } catch (IOException | RuntimeException exception) {
            // Any runtime exception, so that a failure nobody foresaw, such as a table the import left
            // empty, is also recorded and retried. The exception names the step that failed.
            log.error("Feed import failed", exception);
            history.failed(attempt, exception);
        }
        return false;
    }

    // Runs after the import may have replaced the timetable, so a failure is only logged: a feed left
    // behind takes disk space until the next start deletes it, and the import has already succeeded.
    private static void cleanUp(AutoCloseable resource, String name) {
        try {
            resource.close();
        } catch (Exception exception) {
            log.warn("Could not close {}", name, exception);
        }
    }

    private void replaceTimetable(Path feed, ImportAttempt attempt) throws IOException {
        var zip = new ZipFile(feed.toFile());
        try {
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
        } finally {
            cleanUp(zip, "the feed's ZIP file " + feed);
        }
        log.info("Stored the timetable from {}", feed);
    }
}
