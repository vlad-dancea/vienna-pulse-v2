-- One row per import attempt. Every import replaces the timetable schema, so the history lives
-- outside it. A success is recorded in the transaction that swaps in its timetable, so the last
-- successful row always describes the timetable being served.
CREATE TABLE public.imports (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz NOT NULL,
    outcome     text NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    -- Why a failed import failed, empty for a success.
    error       text CHECK ((outcome = 'FAILED') = (error IS NOT NULL)),
    feed_url    text NOT NULL,
    -- Empty when the download failed.
    feed_size   bigint,
    feed_sha256 text,
    -- The rows loaded into each table of the timetable, by table name, for a success only.
    row_counts  jsonb CHECK ((outcome = 'SUCCEEDED') = (row_counts IS NOT NULL))
);
