package com.vladdancea.viennapulse.api;

import java.util.List;

/// A stop's name and timetable-wide modes, empty where no trip lets passengers on or off.
public record StopProperties(String name, List<String> modes) {}
