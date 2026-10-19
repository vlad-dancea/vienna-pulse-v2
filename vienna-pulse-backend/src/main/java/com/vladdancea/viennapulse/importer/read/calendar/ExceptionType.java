package com.vladdancea.viennapulse.importer.read.calendar;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;

/// Whether a [CalendarDate] adds a day to its service or removes one.
@RequiredArgsConstructor
public enum ExceptionType {
    ADDED(1),
    REMOVED(2);

    private final int code;

    public static ExceptionType fromCode(String code) {
        return Arrays.stream(values())
                .filter(type -> String.valueOf(type.code).equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown exception type " + code));
    }
}
