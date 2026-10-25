-- A stop's departures read only its calls in the hours around now. The table is stored trip by
-- trip, so reading all of a busy stop's calls to keep those hours meant reading a third of its pages.
-- The index holds only the calls where passengers can board, which are the only ones departures
-- read, and replaces V7's index on the stop alone, which nothing else reads. The importer copies the
-- table with its indexes into staging, so every import builds this one too.
--
-- The backend runs this before it serves, while the deploy waits for it to get healthy. A session
-- still reading the table makes the migration fail after 10 seconds. Waiting instead, it would hold
-- Flyway's lock past the end of the deploy, and the previous backend, which the deploy then starts,
-- would wait for that lock and fail. Building first leaves the table readable during the long build,
-- and only the drop at the end takes the lock that stops readers.
SET LOCAL lock_timeout = '10s';
CREATE INDEX ON timetable.stop_times (stop_id, departure_time) WHERE pickup_type <> 'NONE';
DROP INDEX timetable.stop_times_stop_id_idx;
