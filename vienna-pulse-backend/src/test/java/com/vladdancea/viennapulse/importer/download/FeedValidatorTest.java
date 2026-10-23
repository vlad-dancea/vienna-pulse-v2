package com.vladdancea.viennapulse.importer.download;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.importer.read.TestFeed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedValidatorTest {
    private final FeedValidator validator = new FeedValidator();

    @TempDir
    Path dir;

    @Test
    void acceptsAWholeFeed() throws IOException {
        var feed = TestFeed.write(dir, TestFeed.fixture());

        assertThatCode(() -> validator.validate(feed)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "agency.txt",
                "routes.txt",
                "trips.txt",
                "stops.txt",
                "stop_times.txt",
                "shapes.txt",
                "calendar.txt",
                "calendar_dates.txt"
            })
    void rejectsAFeedWithoutAFile(String file) throws IOException {
        var files = TestFeed.fixture();
        files.remove(file);
        var feed = TestFeed.write(dir, files);

        assertThatThrownBy(() -> validator.validate(feed))
                .isInstanceOf(ZipException.class)
                .hasMessage("GTFS ZIP is missing a file: " + file);
    }

    @Test
    void rejectsAFeedWithAnEmptyFile() throws IOException {
        var files = TestFeed.fixture();
        files.put("stop_times.txt", "");
        var feed = TestFeed.write(dir, files);

        assertThatThrownBy(() -> validator.validate(feed))
                .isInstanceOf(ZipException.class)
                .hasMessage("GTFS ZIP is missing a file: stop_times.txt");
    }

    // A server can answer 200 with an error page.
    @Test
    void rejectsAFileThatIsNoZip() throws IOException {
        var feed = Files.writeString(dir.resolve("feed.zip"), "<html>Wartungsarbeiten</html>", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(feed)).isInstanceOf(ZipException.class);
    }
}
