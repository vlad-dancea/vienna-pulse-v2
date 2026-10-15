package com.vladdancea.viennapulse.importer.read.route;

import static com.vladdancea.viennapulse.importer.read.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.read.FeedCsv.required;

import com.vladdancea.viennapulse.importer.read.FeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.springframework.stereotype.Component;

/// Reads `routes.txt` into [Route] records.
@Component
public class RouteReader implements FeedFileReader<Route> {
    @Override
    public String file() {
        return "routes.txt";
    }

    @Override
    public Route parse(NamedCsvRecord row) {
        return new Route(
                required(row, "route_id"),
                optional(row, "agency_id"),
                optional(row, "route_short_name"),
                optional(row, "route_long_name"),
                RouteType.fromCode(Integer.parseInt(required(row, "route_type"))),
                optional(row, "route_color"),
                optional(row, "route_text_color"));
    }

    @Override
    public String id(Route route) {
        return route.id();
    }
}
