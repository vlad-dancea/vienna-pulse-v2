package com.vladdancea.viennapulse.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/// Answers the API requests that the database fails in the problem detail format of every other
/// error, instead of Spring Boot's own. No cache may keep the answer, since the next request can
/// succeed.
@RestControllerAdvice(basePackageClasses = DatabaseFailureAdvice.class)
@Slf4j
class DatabaseFailureAdvice {
    // A query that holds a connection is cancelled after 5 seconds, so one is free again by then.
    private static final String RETRY_AFTER_SECONDS = "5";

    /// A 503 for a database that cannot be reached, no free connection in the pool and a query over
    /// the time limit, which all pass on their own.
    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        TransientDataAccessException.class,
        RecoverableDataAccessException.class
    })
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception, HttpServletRequest request) {
        log.warn("The database failed to answer {}", request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The timetable database is not answering, try again in a few seconds"));
    }

    /// A 500 for any other database failure, such as a mistake in a query, which no retry fixes.
    @ExceptionHandler
    ResponseEntity<ProblemDetail> failed(DataAccessException exception, HttpServletRequest request) {
        log.error("Reading the timetable failed for {}", request.getRequestURI(), exception);
        return ResponseEntity.internalServerError()
                .cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Reading the timetable failed"));
    }
}
