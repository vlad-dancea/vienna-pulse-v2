-- When each trip calls at each platform, one row per call. Times are seconds after noon minus
-- 12 hours on the service day and can pass 24 hours, which no time-of-day type can hold. The stop
-- time reader streams the file, so the primary key is what rejects a sequence number used twice.
CREATE TABLE timetable.stop_times (
    trip_id           text NOT NULL,
    sequence          integer NOT NULL,
    stop_id           text NOT NULL,
    arrival_time      integer,
    departure_time    integer,
    pickup_type       text NOT NULL,
    drop_off_type     text NOT NULL,
    distance_traveled double precision,
    PRIMARY KEY (trip_id, sequence)
);
