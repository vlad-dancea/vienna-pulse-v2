-- Wiener Linien's live departures from the stations people had open on the departure board, one row
-- per departure, for statistics of how punctual the lines are. Every import replaces the timetable
-- schema, so the rows live outside it. Each round updates the rows of the departures its answer
-- lists, so a row keeps the last prediction before the vehicle left, or before the board closed the
-- station.
CREATE TABLE public.live_departures (
    -- The platform's number in the realtime API, which requests call `stopId`.
    platform     integer NOT NULL,
    -- The line's name and direction as the answers give them, such as U1 and H. A departure keeps the
    -- time Wiener Linien plan it for while its live time moves.
    line         text NOT NULL,
    direction    text NOT NULL,
    planned_time timestamptz NOT NULL,
    -- The number of the platform's station. The map's stop `at:49:1320` has the station 60201320.
    station      integer NOT NULL,
    -- When Wiener Linien last expected the departure, null when they had no live time for it, and the
    -- minutes their displays counted down to it.
    live_time    timestamptz,
    countdown    integer NOT NULL,
    -- When the first and the last round that listed it asked.
    first_seen   timestamptz NOT NULL,
    last_seen    timestamptz NOT NULL,
    -- The trip and route of the timetable departure it was paired with, null while none was. They are
    -- the ids of the timetable at the time, which a later import may no longer have.
    trip_id      text,
    route_id     text,
    PRIMARY KEY (platform, line, direction, planned_time)
-- Rounds rewrite a row every 15 seconds while its station is open. The space each page keeps free lets
-- PostgreSQL write the new version into the same page, without a new entry in the primary key.
) WITH (fillfactor = 70);

-- The departures a round saw leave: those with a live time that a round listed a minute or less before
-- it. The others are predictions from a board that closed earlier. Their delay is the live time minus
-- the planned one.
CREATE VIEW public.observed_departures AS
SELECT platform,
       line,
       direction,
       planned_time,
       station,
       live_time,
       live_time - planned_time AS delay,
       last_seen,
       trip_id,
       route_id
FROM public.live_departures
WHERE last_seen >= live_time - interval '1 minute';
