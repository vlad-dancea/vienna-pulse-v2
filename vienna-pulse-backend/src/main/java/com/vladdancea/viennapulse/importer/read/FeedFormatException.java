package com.vladdancea.viennapulse.importer.read;

import java.util.Objects;

/// A feed file whose content breaks the GTFS rules, with the file and line where it happened.
public class FeedFormatException extends RuntimeException {
    FeedFormatException(String message) {
        super(message);
    }

    public FeedFormatException(String message, Throwable cause) {
        super(message, cause);
    }

    static FeedFormatException at(String file, long line, String problem) {
        return new FeedFormatException("%s line %d: %s".formatted(file, line, problem));
    }

    public static FeedFormatException at(String file, long line, Throwable cause) {
        var problem = Objects.requireNonNullElse(cause.getMessage(), cause.toString());
        return new FeedFormatException("%s line %d: %s".formatted(file, line, problem), cause);
    }
}
