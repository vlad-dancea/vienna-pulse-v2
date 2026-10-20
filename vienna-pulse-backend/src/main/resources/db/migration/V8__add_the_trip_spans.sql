-- When each trip leaves its first stop and reaches its last, in the seconds of the stop times. The
-- vehicles on the map are the trips running at one moment, which would otherwise take reading the
-- first and last call of every trip of the day. The importer derives the table from the stop times.
CREATE TABLE timetable.trip_spans (
    trip_id         text PRIMARY KEY,
    service_id      text NOT NULL,
    first_departure integer NOT NULL,
    last_arrival    integer NOT NULL
);
CREATE INDEX ON timetable.trip_spans (service_id, first_departure);
