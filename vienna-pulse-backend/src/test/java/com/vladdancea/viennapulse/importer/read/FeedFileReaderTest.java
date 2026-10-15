package com.vladdancea.viennapulse.importer.read;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedFileReaderTest {
    private static final FeedFileReader<String> NAMES = new FeedFileReader<>() {
        @Override
        public String file() {
            return "names.txt";
        }

        @Override
        public String parse(NamedCsvRecord row) {
            return FeedCsv.required(row, "name");
        }

        @Override
        public String id(String name) {
            return name;
        }
    };

    @TempDir
    Path dir;

    @Test
    void rejectsDuplicateIdsWithTheirLine() throws Exception {
        try (var feed = TestFeed.zip(dir, "names.txt", "name\na\nb\na\n")) {
            assertThatThrownBy(() -> NAMES.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("names.txt line 4: duplicate id a");
        }
    }

    @Test
    void reportsTheLineOfABadRow() throws Exception {
        try (var feed = TestFeed.zip(dir, "names.txt", "name\na\n\"\"\n")) {
            assertThatThrownBy(() -> NAMES.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("names.txt line 3: missing name");
        }
    }

    @Test
    void rejectsAFeedWithoutTheFile() throws Exception {
        try (var feed = TestFeed.zip(dir, "other.txt", "name\na\n")) {
            assertThatThrownBy(() -> NAMES.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("GTFS feed is missing names.txt");
        }
    }

    @Test
    void rejectsInvalidCsv() throws Exception {
        try (var feed = TestFeed.zip(dir, "names.txt", "name\n\"a\"b\n")) {
            assertThatThrownBy(() -> NAMES.read(feed))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("names.txt is not valid CSV");
        }
    }

    @Test
    void throwsTheIoExceptionWhenTheDataCannotBeRead() throws Exception {
        var path = TestFeed.write(dir, "names.txt", "name\na\n");
        try (var feed = new ZipFile(path.toFile()) {
            @Override
            public InputStream getInputStream(ZipEntry entry) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("disk error");
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        throw new IOException("disk error");
                    }
                };
            }
        }) {
            assertThatThrownBy(() -> NAMES.read(feed))
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage("disk error");
        }
    }
}
