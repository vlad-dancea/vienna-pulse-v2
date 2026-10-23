-- The rules every query of the timetable follows, written once. Each import replaces the timetable
-- schema, so they live in a schema of their own. Their bodies are plain strings, which PostgreSQL
-- reads again on each call, so they always find the current timetable. A BEGIN ATOMIC body would
-- bind to the tables it names, follow them into timetable_old at the next import and break when
-- that is dropped. Plain SQL bodies also let the planner inline them into the calling query.
CREATE SCHEMA pulse;

-- The services whose trips run on `day`: those whose weekly pattern between its first and last date
-- includes the day, minus those calendar_dates.txt removes on it, plus those it adds.
CREATE FUNCTION pulse.running_services(day date) RETURNS TABLE (service_id text)
LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    SELECT service_id
    FROM timetable.service_calendars
    WHERE day BETWEEN start_date AND end_date
      AND CASE extract(isodow FROM day)
              WHEN 1 THEN monday
              WHEN 2 THEN tuesday
              WHEN 3 THEN wednesday
              WHEN 4 THEN thursday
              WHEN 5 THEN friday
              WHEN 6 THEN saturday
              WHEN 7 THEN sunday
          END
    EXCEPT
    SELECT service_id
    FROM timetable.calendar_dates
    WHERE date = day AND exception_type = 'REMOVED'
    UNION
    SELECT service_id
    FROM timetable.calendar_dates
    WHERE date = day AND exception_type = 'ADDED'
$$;

-- The function looks up the services added and removed on each day it is called for, three or four
-- times per query. Without an index each lookup reads all of the calendar dates.
CREATE INDEX ON timetable.calendar_dates (date);

-- When the times of `day` start counting: noon minus 12 hours, which is midnight except on the
-- days the clocks change. The feed writes its times in Vienna's time zone.
CREATE FUNCTION pulse.service_day_origin(day date) RETURNS timestamptz
LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    SELECT (day + time '12:00') AT TIME ZONE 'Europe/Vienna' - interval '12 hours'
$$;
