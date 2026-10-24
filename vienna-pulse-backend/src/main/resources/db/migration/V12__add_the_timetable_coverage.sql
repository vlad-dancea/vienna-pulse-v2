-- The first and last day the timetable covers, null while it is empty: from the first date of a
-- service's weekly pattern or added day to the last. Its body is a plain string like those of the
-- V10 rules, so it follows the timetable an import swaps in. It returns a table, which the planner
-- can inline into the query that reads it.
CREATE FUNCTION pulse.coverage() RETURNS TABLE (first_date date, last_date date)
LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    SELECT min(first_date), max(last_date)
    FROM (
        SELECT start_date AS first_date, end_date AS last_date
        FROM timetable.service_calendars
        UNION ALL
        SELECT date, date
        FROM timetable.calendar_dates
        WHERE exception_type = 'ADDED'
    ) AS days
$$;
