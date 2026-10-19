-- The paths vehicles follow, one row per point. A shape's points can be scattered through the
-- file, so the primary key is what rejects a sequence number used twice within a shape.
CREATE TABLE timetable.shape_points (
    shape_id          text NOT NULL,
    sequence          integer NOT NULL,
    latitude          double precision NOT NULL,
    longitude         double precision NOT NULL,
    distance_traveled double precision,
    PRIMARY KEY (shape_id, sequence)
);
