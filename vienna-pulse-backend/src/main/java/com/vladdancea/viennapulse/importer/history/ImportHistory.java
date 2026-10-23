package com.vladdancea.viennapulse.importer.history;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/// Writes one row per import attempt into `public.imports` and reads the last success back.
@Component
@Slf4j
@RequiredArgsConstructor
public class ImportHistory {
    private final JdbcClient jdbc;
    private final Clock clock;

    /// Records a successful import. Call it inside the transaction that swaps in the timetable, so
    /// that the record commits with the timetable it describes, or not at all.
    public void succeeded(ImportAttempt attempt, Map<String, Long> rowCounts) {
        insert(attempt, "SUCCEEDED", null, JsonMapper.shared().writeValueAsString(rowCounts));
    }

    /// Records a failed import with the exception that stopped it. A record that cannot be written,
    /// because the database is down for example, is logged instead of thrown, since the import has
    /// already failed and been logged.
    public void failed(ImportAttempt attempt, Exception exception) {
        try {
            insert(attempt, "FAILED", exception.toString(), null);
        } catch (DataAccessException recordFailure) {
            log.error("Could not record the failed import", recordFailure);
        }
    }

    /// When the last successful import finished, empty when none is recorded.
    public Optional<Instant> lastSuccess() {
        return jdbc.sql("""
                        SELECT finished_at FROM public.imports
                        WHERE outcome = 'SUCCEEDED'
                        ORDER BY finished_at DESC
                        LIMIT 1
                        """)
                .query((row, number) ->
                        row.getObject("finished_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    private void insert(ImportAttempt attempt, String outcome, @Nullable String error, @Nullable String rowCounts) {
        var feed = attempt.feed();
        jdbc.sql("""
                        INSERT INTO public.imports
                            (started_at, finished_at, outcome, error, feed_url, feed_size, feed_sha256, row_counts)
                        VALUES
                            (:startedAt, :finishedAt, :outcome, :error, :feedUrl, :feedSize, :feedSha256,
                             CAST(:rowCounts AS jsonb))
                        """)
                .param("startedAt", attempt.startedAt().atOffset(ZoneOffset.UTC))
                .param("finishedAt", clock.instant().atOffset(ZoneOffset.UTC))
                .param("outcome", outcome)
                .param("error", error)
                .param("feedUrl", attempt.feedUrl().toString())
                .param("feedSize", feed == null ? null : feed.size())
                .param("feedSha256", feed == null ? null : feed.sha256())
                .param("rowCounts", rowCounts)
                .update();
    }
}
