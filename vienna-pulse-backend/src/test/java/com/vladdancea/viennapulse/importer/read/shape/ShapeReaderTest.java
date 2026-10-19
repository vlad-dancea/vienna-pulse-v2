package com.vladdancea.viennapulse.importer.read.shape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vladdancea.viennapulse.importer.read.FeedFormatException;
import com.vladdancea.viennapulse.importer.read.TestFeed;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ShapeReaderTest {
    private static final String HEADER = "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence,shape_dist_traveled\n";

    private final ShapeReader reader = new ShapeReader();

    @TempDir
    Path dir;

    @Test
    void readsPointsInFileOrderWithoutGroupingOrSorting() throws Exception {
        var shapes = """
                \uFEFF"shape_id","shape_pt_lat","shape_pt_lon","shape_pt_sequence","shape_dist_traveled"
                "outbound","48.12921691","16.31007712","0","0.00"
                "return","-90","-180","4",""
                "outbound","48.12936681","16.31014836","7","17.49"
                "outbound","48.12939559","16.31015761","3","17.49"
                "return","90","180","8",""
                """;
        var points = new ArrayList<ShapePoint>();
        try (var feed = TestFeed.zip(dir, "shapes.txt", shapes)) {
            reader.read(feed, points::add);

            assertThat(points)
                    .containsExactly(
                            new ShapePoint("outbound", 48.12921691, 16.31007712, 0, 0.0),
                            new ShapePoint("return", -90, -180, 4, null),
                            new ShapePoint("outbound", 48.12936681, 16.31014836, 7, 17.49),
                            new ShapePoint("outbound", 48.12939559, 16.31015761, 3, 17.49),
                            new ShapePoint("return", 90, 180, 8, null));
            assertThat(feed.getEntry("shapes.txt")).isNotNull();
        }
    }

    @Test
    void readsWithoutTheOptionalDistanceColumn() throws Exception {
        var shapes = "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n s1 ,48.2,16.3,0\n";
        var points = new ArrayList<ShapePoint>();
        try (var feed = TestFeed.zip(dir, "shapes.txt", shapes)) {
            reader.read(feed, points::add);
            assertThat(points).containsExactly(new ShapePoint("s1", 48.2, 16.3, 0, null));
        }
    }

    @Test
    void deliversEachPointBeforeReadingTheNextRow() throws Exception {
        var shapes = HEADER + "s1,48.2,16.3,0,0\ns1,north,16.3,1,1\n";
        var points = new ArrayList<ShapePoint>();
        try (var feed = TestFeed.zip(dir, "shapes.txt", shapes)) {
            assertThatThrownBy(() -> reader.read(feed, points::add))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("shapes.txt line 3: shape_pt_lat north is not a number");
            assertThat(points).containsExactly(new ShapePoint("s1", 48.2, 16.3, 0, 0.0));
        }
    }

    @Test
    void readsAFileWithoutPoints() throws Exception {
        var points = new ArrayList<ShapePoint>();
        try (var feed = TestFeed.zip(dir, "shapes.txt", HEADER)) {
            reader.read(feed, points::add);
            assertThat(points).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    ,48.2,16.3,0,0             | missing shape_id
                    s1,,16.3,0,0               | missing shape_pt_lat
                    s1,48.2,,0,0               | missing shape_pt_lon
                    s1,48.2,16.3,,0            | missing shape_pt_sequence
                    s1,north,16.3,0,0          | shape_pt_lat north is not a number
                    s1,48.2,east,0,0           | shape_pt_lon east is not a number
                    s1,91,16.3,0,0             | shape_pt_lat 91.0 is not between -90 and 90
                    s1,-91,16.3,0,0            | shape_pt_lat -91.0 is not between -90 and 90
                    s1,48.2,181,0,0            | shape_pt_lon 181.0 is not between -180 and 180
                    s1,48.2,-181,0,0           | shape_pt_lon -181.0 is not between -180 and 180
                    s1,NaN,16.3,0,0            | shape_pt_lat NaN is not between -90 and 90
                    s1,48.2,Infinity,0,0       | shape_pt_lon Infinity is not between -180 and 180
                    s1,48.2,16.3,-1,0          | shape_pt_sequence -1 is negative
                    s1,48.2,16.3,1.5,0         | shape_pt_sequence 1.5 is not an integer
                    s1,48.2,16.3,2147483648,0  | shape_pt_sequence 2147483648 is out of range
                    s1,48.2,16.3,0,far         | shape_dist_traveled far is not a number
                    s1,48.2,16.3,0,-1          | shape_dist_traveled -1.0 is negative
                    s1,48.2,16.3,0,NaN         | shape_dist_traveled NaN is not finite
                    s1,48.2,16.3,0,Infinity    | shape_dist_traveled Infinity is not finite
                    """)
    void rejectsRowsThatBreakTheGtfsRules(String row, String problem) throws Exception {
        try (var feed = TestFeed.zip(dir, "shapes.txt", HEADER + row + "\n")) {
            assertThatThrownBy(() -> reader.read(feed, point -> {}))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("shapes.txt line 2: " + problem);
        }
    }

    @Test
    void rejectsAFeedWithoutTheFile() throws Exception {
        try (var feed = TestFeed.zip(dir, "other.txt", "unused\n")) {
            assertThatThrownBy(() -> reader.read(feed, point -> {}))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("GTFS feed is missing shapes.txt");
        }
    }

    @Test
    void rejectsMalformedCsv() throws Exception {
        try (var feed = TestFeed.zip(dir, "shapes.txt", HEADER + "\"s1\"broken,48.2,16.3,0,0\n")) {
            assertThatThrownBy(() -> reader.read(feed, point -> {}))
                    .isInstanceOf(FeedFormatException.class)
                    .hasMessage("shapes.txt is not valid CSV");
        }
    }

    @Test
    void stopsImmediatelyWhenTheConsumerFailsAndClosesTheEntry() throws Exception {
        var path = TestFeed.write(dir, "shapes.txt", HEADER + "s1,48.2,16.3,0,0\ns1,north,16.3,1,1\n");
        var closed = new boolean[1];
        var failure = new IllegalArgumentException("consumer failed");
        try (var feed = new ZipFile(path.toFile()) {
            @Override
            public InputStream getInputStream(ZipEntry entry) throws IOException {
                return new FilterInputStream(super.getInputStream(entry)) {
                    @Override
                    public void close() throws IOException {
                        closed[0] = true;
                        super.close();
                    }
                };
            }
        }) {
            assertThatThrownBy(() -> reader.read(feed, point -> {
                        throw failure;
                    }))
                    .isSameAs(failure);
            assertThat(closed[0]).isTrue();
            assertThat(feed.getEntry("shapes.txt")).isNotNull();
        }
    }

    @Test
    void throwsTheIoExceptionWhenTheDataCannotBeRead() throws Exception {
        var path = TestFeed.write(dir, "shapes.txt", HEADER);
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
            assertThatThrownBy(() -> reader.read(feed, point -> {}))
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage("disk error");
        }
    }
}
