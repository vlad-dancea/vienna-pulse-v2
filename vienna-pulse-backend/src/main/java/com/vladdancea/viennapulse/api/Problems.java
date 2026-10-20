package com.vladdancea.viennapulse.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/// Error responses shared by the API endpoints.
final class Problems {
    private Problems() {}

    /// A 404 with `detail` as its problem detail. Caches may keep a 404 without being told, but the
    /// next import can add what was missing, so they must ask again like for any other response.
    static ErrorResponseException notFound(String detail) {
        var exception = new ErrorResponseException(
                HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail), null);
        exception.getHeaders().setCacheControl(CacheControl.noCache());
        return exception;
    }
}
