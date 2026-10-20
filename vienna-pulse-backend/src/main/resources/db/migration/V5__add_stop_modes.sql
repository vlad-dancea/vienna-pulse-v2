-- One classification per platform, including an empty array for unused platforms. A populated
-- table also distinguishes a completed import from an upgrade that still needs stop_times.txt.
CREATE TABLE timetable.stop_modes (
    stop_id text PRIMARY KEY,
    types   text[] NOT NULL
);
