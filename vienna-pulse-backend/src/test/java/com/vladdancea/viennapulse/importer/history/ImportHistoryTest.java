package com.vladdancea.viennapulse.importer.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.vladdancea.viennapulse.TestcontainersConfiguration;
import com.vladdancea.viennapulse.importer.ImportJob;
import com.vladdancea.viennapulse.importer.ImporterProperties;
import com.vladdancea.viennapulse.importer.download.DownloadedFeed;
import com.vladdancea.viennapulse.importer.download.FeedDownloader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// Runs the whole import, with only the download replaced, and reads back what it recorded.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ImportHistoryTest {
    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");
    private static final Map<String, String> FEED = feed("1.T0.U1.1.H,U1,T0,U1.1.H,Oberlaa,0\n");

    // What the next download returns or throws, set by each test.
    private static Supplier<DownloadedFeed> nextDownload = () -> {
        throw new IllegalStateException("The test set no download");
    };

    @TempDir
    Path dir;

    @Autowired
    ImportJob importJob;

    @Autowired
    ImporterProperties properties;

    @Autowired
    JdbcClient jdbc;

    // Never read: the fields only name the beans that the methods of the same name replace.
    @TestBean
    @Nullable
    FeedDownloader downloader;

    @TestBean
    @Nullable
    Clock clock;

    static FeedDownloader downloader() {
        return new FeedDownloader(
                new ImporterProperties(URI.create("http://localhost/"), false), RestClient.builder()) {
            @Override
            public DownloadedFeed download() {
                return nextDownload.get();
            }

            @Override
            public void deleteLeftovers() {}
        };
    }

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @BeforeEach
    void forgetEarlierImports() {
        jdbc.sql("DELETE FROM public.imports").update();
    }

    @Test
    void recordsASuccessWithTheRowsOfEachTable() throws IOException {
        var feed = zip(FEED);

        run(feed);

        assertThat(imports())
                .containsExactly(new ImportRow(
                        NOW,
                        NOW,
                        "SUCCEEDED",
                        null,
                        properties.feedUrl().toString(),
                        Files.size(feed),
                        sha256(feed),
                        Map.of(
                                "calendar_dates", 1L,
                                "routes", 1L,
                                "service_calendars", 1L,
                                "shape_points", 2L,
                                "stop_modes", 2L,
                                "stop_times", 2L,
                                "stops", 2L,
                                "trip_spans", 1L,
                                "trips", 1L)));
    }

    // The swap has committed by then, so the import has succeeded and must not be recorded twice.
    @Test
    void recordsOnlyTheSuccessWhenTheFeedCannotBeDeleted() throws IOException {
        var readOnly = Files.createDirectory(dir.resolve("read-only"));
        var copy = Files.copy(zip(FEED), readOnly.resolve("download.zip"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-x------"));
        try {
            assumeFalse(Files.isWritable(readOnly), "Runs as a user who can delete from a read-only folder");
            nextDownload = () -> new DownloadedFeed(copy);

            importJob.run();

            assertThat(copy).exists();
            assertThat(imports()).extracting(ImportRow::outcome).containsExactly("SUCCEEDED");
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void recordsAFailedDownload() {
        nextDownload = () -> {
            throw new RestClientException("GTFS download failed with HTTP 503");
        };

        importJob.run();

        assertThat(imports())
                .containsExactly(new ImportRow(
                        NOW,
                        NOW,
                        "FAILED",
                        "org.springframework.web.client.RestClientException: GTFS download failed with HTTP 503",
                        properties.feedUrl().toString(),
                        null,
                        null,
                        null));
    }

    @Test
    void recordsAFeedThatMissesAFile() throws IOException {
        var feed = zip(Map.of("agency.txt", FEED.get("agency.txt")));

        run(feed);

        assertThat(imports())
                .containsExactly(new ImportRow(
                        NOW,
                        NOW,
                        "FAILED",
                        "java.util.zip.ZipException: GTFS ZIP is missing a file: routes.txt",
                        properties.feedUrl().toString(),
                        Files.size(feed),
                        sha256(feed),
                        null));
    }

    @Test
    void recordsADatabaseError() throws IOException {
        var repeatedTrip = "1.T0.U1.1.H,U1,T0,U1.1.H,Oberlaa,0\n";
        var feed = zip(feed(repeatedTrip + repeatedTrip));

        run(feed);

        var row = imports().getFirst();
        assertThat(row.outcome()).isEqualTo("FAILED");
        assertThat(row.error())
                .startsWith("org.springframework.jdbc.UncategorizedSQLException: Copying into staging.trips")
                .contains("Key (id)=(1.T0.U1.1.H) already exists.");
        assertThat(row.feedSha256()).isEqualTo(sha256(feed));
        assertThat(row.rowCounts()).isNull();
    }

    private void run(Path feed) throws IOException {
        // The import deletes its feed, so it gets a copy and the test keeps the original to compare.
        var copy = Files.copy(feed, dir.resolve("download.zip"));
        nextDownload = () -> new DownloadedFeed(copy);
        importJob.run();
        assertThat(copy).doesNotExist();
    }

    private Path zip(Map<String, String> files) throws IOException {
        var path = dir.resolve("feed.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return path;
    }

    // A complete feed of one trip between two stops, with `trips` as the rows of trips.txt.
    private static Map<String, String> feed(String trips) {
        var files = new LinkedHashMap<String, String>();
        files.put("agency.txt", """
                agency_id,agency_name,agency_url,agency_timezone
                wl,Wiener Linien,https://www.wienerlinien.at,Europe/Vienna
                """);
        files.put("routes.txt", """
                route_id,agency_id,route_short_name,route_long_name,route_type,route_color,route_text_color
                U1,wl,U1,Leopoldau - Oberlaa,1,E3000F,FFFFFF
                """);
        files.put("stops.txt", """
                stop_id,stop_name,stop_lat,stop_lon
                s1,Karlsplatz,48.20024,16.36951
                s2,Stephansplatz,48.20845,16.37212
                """);
        files.put("shapes.txt", """
                shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence,shape_dist_traveled
                U1.1.H,48.20024,16.36951,1,0
                U1.1.H,48.20845,16.37212,2,930
                """);
        files.put("trips.txt", "trip_id,route_id,service_id,shape_id,trip_headsign,direction_id\n" + trips);
        files.put("stop_times.txt", """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence,shape_dist_traveled
                1.T0.U1.1.H,08:00:00,08:00:00,s1,1,0
                1.T0.U1.1.H,08:02:00,08:02:00,s2,2,930
                """);
        files.put("calendar.txt", """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                T0,1,1,1,1,1,0,0,20261001,20261212
                """);
        files.put("calendar_dates.txt", """
                service_id,date,exception_type
                T0,20261026,2
                """);
        return files;
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<ImportRow> imports() {
        return jdbc.sql("SELECT * FROM public.imports ORDER BY id")
                .query((row, number) -> {
                    var rowCounts = row.getString("row_counts");
                    return new ImportRow(
                            row.getObject("started_at", OffsetDateTime.class).toInstant(),
                            row.getObject("finished_at", OffsetDateTime.class).toInstant(),
                            row.getString("outcome"),
                            row.getString("error"),
                            row.getString("feed_url"),
                            row.getObject("feed_size", Long.class),
                            row.getString("feed_sha256"),
                            rowCounts == null
                                    ? null
                                    : JsonMapper.shared().readValue(rowCounts, new TypeReference<>() {}));
                })
                .list();
    }

    private record ImportRow(
            Instant startedAt,
            Instant finishedAt,
            String outcome,
            @Nullable String error,
            String feedUrl,
            @Nullable Long feedSize,
            @Nullable String feedSha256,
            @Nullable Map<String, Long> rowCounts) {}
}
