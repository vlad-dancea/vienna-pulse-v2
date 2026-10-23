package com.vladdancea.viennapulse.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vladdancea.viennapulse.importer.download.DownloadedFeed;
import com.vladdancea.viennapulse.importer.download.FeedDownloader;
import com.vladdancea.viennapulse.importer.download.FeedValidator;
import com.vladdancea.viennapulse.importer.read.TestFeed;
import com.vladdancea.viennapulse.importer.read.calendar.CalendarDateReader;
import com.vladdancea.viennapulse.importer.read.calendar.ServiceCalendarReader;
import com.vladdancea.viennapulse.importer.read.route.RouteReader;
import com.vladdancea.viennapulse.importer.read.shape.ShapeReader;
import com.vladdancea.viennapulse.importer.read.stop.StopReader;
import com.vladdancea.viennapulse.importer.read.stoptime.StopTimeReader;
import com.vladdancea.viennapulse.importer.read.trip.TripReader;
import com.vladdancea.viennapulse.importer.store.Store;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.zip.ZipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClientException;

class ImportJobTest {
    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private final FeedDownloader downloader = mock();
    private final FeedValidator validator = mock();
    private final Store store = mock();
    private final TaskScheduler scheduler = mock();
    private final ScheduledFuture<?> retry = mock();
    private final List<Runnable> retries = new ArrayList<>();
    private final List<Instant> retryTimes = new ArrayList<>();
    private final ImportJob job = new ImportJob(
            downloader,
            validator,
            mock(RouteReader.class),
            mock(StopReader.class),
            mock(ShapeReader.class),
            mock(TripReader.class),
            mock(StopTimeReader.class),
            mock(ServiceCalendarReader.class),
            mock(CalendarDateReader.class),
            store,
            new ImporterProperties(
                    URI.create("https://example.com/gtfs.zip"),
                    DataSize.ofMegabytes(500),
                    false,
                    List.of(Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(3))),
            scheduler);

    @TempDir
    Path dir;

    @BeforeEach
    void recordRetries() {
        when(scheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            retries.add(invocation.getArgument(0));
            retryTimes.add(invocation.getArgument(1));
            return retry;
        });
    }

    @Test
    void retriesAFailedImportWithGrowingPausesThenWaitsForTheNextRun() {
        when(downloader.download()).thenThrow(new RestClientException("wienerlinien.at is down"));

        job.run();
        runRetries();

        assertThat(retryTimes)
                .containsExactly(
                        NOW.plus(Duration.ofMinutes(10)), NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(3)));
        verify(downloader, times(4)).download();
    }

    @Test
    void aSuccessfulRetryStopsTheRetries() throws IOException {
        when(downloader.download()).thenAnswer(_ -> new DownloadedFeed(TestFeed.write(dir, "agency.txt", "agency")));
        // A broken feed is retried too, because Wiener Linien may replace it within hours.
        doThrow(new ZipException("GTFS ZIP is missing a file: stops.txt"))
                .doNothing()
                .when(validator)
                .validate(any());

        job.run();
        runRetries();

        assertThat(retryTimes).containsExactly(NOW.plus(Duration.ofMinutes(10)));
        verify(store).replace(any());
    }

    @Test
    void aScheduledRunCancelsTheRetriesOfTheRunBefore() {
        when(downloader.download()).thenThrow(new RestClientException("wienerlinien.at is down"));

        job.run();
        job.run();

        verify(retry).cancel(false);
        assertThat(retryTimes).hasSize(2);
    }

    // Runs each retry when it comes due, as the scheduler would, including the ones it schedules.
    private void runRetries() {
        for (var i = 0; i < retries.size(); i++) {
            retries.get(i).run();
        }
    }
}
