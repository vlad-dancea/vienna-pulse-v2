-- The stop each platform belongs to, which the map shows: the first three parts of its IFOPT-style
-- id, like at:49:1320 for at:49:1320:0:3. Stored, it can be indexed for finding a stop's platforms.
-- The importer copies the table with its generated columns and indexes, so every import fills it.
-- A generated column needs an immutable expression, which the regular expression is and splitting
-- the id into an array is not.
ALTER TABLE timetable.stops
    ADD COLUMN merged_id text NOT NULL GENERATED ALWAYS AS (substring(id FROM '^[^:]*(?::[^:]*){0,2}')) STORED;
CREATE INDEX ON timetable.stops (merged_id);
