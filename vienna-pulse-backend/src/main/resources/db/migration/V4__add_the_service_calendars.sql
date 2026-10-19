-- The days each service runs, and so each of its trips: a weekly pattern between two dates, then
-- single days added or removed. A service may appear in only one of the two tables.
CREATE TABLE timetable.service_calendars (
    service_id text PRIMARY KEY,
    monday     boolean NOT NULL,
    tuesday    boolean NOT NULL,
    wednesday  boolean NOT NULL,
    thursday   boolean NOT NULL,
    friday     boolean NOT NULL,
    saturday   boolean NOT NULL,
    sunday     boolean NOT NULL,
    start_date date NOT NULL,
    end_date   date NOT NULL
);

CREATE TABLE timetable.calendar_dates (
    service_id     text NOT NULL,
    date           date NOT NULL,
    exception_type text NOT NULL,
    PRIMARY KEY (service_id, date)
);
