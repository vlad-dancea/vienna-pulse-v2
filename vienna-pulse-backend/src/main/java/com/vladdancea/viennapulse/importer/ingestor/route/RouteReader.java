package com.vladdancea.viennapulse.importer.ingestor.route;

import static com.vladdancea.viennapulse.importer.ingestor.FeedCsv.optional;
import static com.vladdancea.viennapulse.importer.ingestor.FeedCsv.required;

import com.vladdancea.viennapulse.importer.ingestor.FeedFileReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import org.springframework.stereotype.Component;

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
