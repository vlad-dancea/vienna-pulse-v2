package com.vladdancea.viennapulse.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/// A stop's name and timetable-wide modes, with null modes while classification is pending.
public record StopProperties(String name, @Nullable List<String> modes) {}
