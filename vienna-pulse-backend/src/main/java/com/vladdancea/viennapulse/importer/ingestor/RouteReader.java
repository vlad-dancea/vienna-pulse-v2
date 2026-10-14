package com.vladdancea.viennapulse.importer.ingestor;

import static com.vladdancea.viennapulse.importer.ingestor.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.ingestor.FeedCsv.required;

import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

@Component
public class RouteReader {
    static final String FILE = "routes.txt";

    /** Reads every route, keyed by id in file order. Fails on the first bad row, naming its line. */
    public SequencedMap<String, Route> read(ZipFile feed) throws IOException {
        var routes = new LinkedHashMap<String, Route>();
        try (var csv = FeedCsv.open(feed, FILE)) {
            for (var row : csv) {
                var route = parse(row);
                if (routes.putIfAbsent(route.id(), route) != null) {
                    throw FeedFormatException.at(FILE, row.getStartingLineNumber(), "duplicate route " + route.id());
                }
            }
        } catch (CsvParseException exception) {
            throw new FeedFormatException(FILE + " is not valid CSV", exception);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        return Collections.unmodifiableSequencedMap(routes);
    }

    private static Route parse(NamedCsvRecord row) {
        try {
            return new Route(
                    required(row, "route_id"),
                    optional(row, "agency_id"),
                    optional(row, "route_short_name"),
                    optional(row, "route_long_name"),
                    RouteType.fromCode(Integer.parseInt(required(row, "route_type"))),
                    optional(row, "route_color"),
                    optional(row, "route_text_color"));
        } catch (IllegalArgumentException exception) {
            throw FeedFormatException.at(FILE, row.getStartingLineNumber(), exception);
        }
    }
}
