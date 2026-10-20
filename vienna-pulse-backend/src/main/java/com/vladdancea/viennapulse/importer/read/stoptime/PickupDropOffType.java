package com.vladdancea.viennapulse.importer.read.stoptime;

import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/// Whether passengers can board (`pickup_type`) or alight (`drop_off_type`) at a [StopTime].
@RequiredArgsConstructor
public enum PickupDropOffType {
    REGULAR("0"),
    NONE("1"),
    PHONE_AGENCY("2"),
    COORDINATE_WITH_DRIVER("3");

    private final String code;

    public static Optional<PickupDropOffType> fromCode(String code) {
        return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst();
    }
}
