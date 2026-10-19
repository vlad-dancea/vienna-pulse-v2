-- Every journey in the timetable, linking a route to the days it runs (service) and the path it
-- follows (shape). The trip reader streams the file, so the primary key is what rejects a repeated id.
CREATE TABLE timetable.trips (
    id           text PRIMARY KEY,
    route_id     text NOT NULL,
    service_id   text NOT NULL,
    shape_id     text,
    headsign     text,
    direction_id smallint
);
