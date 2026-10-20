-- A stop's departures read only its own calls, a few thousand of the millions in the table. The
-- importer copies the table with its indexes into staging, so every import builds this one too.
CREATE INDEX ON timetable.stop_times (stop_id);
