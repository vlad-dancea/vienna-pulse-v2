-- The timetable from the GTFS feed. The importer replaces this whole schema every morning by
-- loading a copy and renaming it into place, so only feed data belongs here: types,
-- functions and anything that must survive an import go in another schema.
CREATE SCHEMA timetable;

-- Columns are named after the record components, so query results map straight onto records.
-- The importer validates every row, so the constraints here only guard the keys and nulls.

CREATE TABLE timetable.routes (
    id         text PRIMARY KEY,
    agency_id  text,
    short_name text,
    long_name  text,
    type       text NOT NULL,
    color      text,
    text_color text
);

CREATE TABLE timetable.stops (
    id        text PRIMARY KEY,
    name      text NOT NULL,
    latitude  double precision NOT NULL,
    longitude double precision NOT NULL,
    zone_id   text
);
