package com.vladdancea.viennapulse.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// Pairs a station's timetable departures with Wiener Linien's live ones, which have no trip id.
///
/// A pair has the same line, the same direction and planned times at most two minutes apart, and
/// the closest pairs come first. Compared at five busy stations on 2026-10-24, Wiener Linien plan
/// some platforms a minute or two later than the timetable, such as tram 52's at Westbahnhof, and
/// their U-Bahn times have half minutes that the timetable rounds either way. Their directions, `H`
/// and `R`, are the timetable's direction ids 0 and 1 on most lines but the other way round on a
/// few, such as bus 5B and the Badner Bahn, so each line takes the reading that pairs more of its
/// departures, and the usual one on a tie.
final class Matching {
    private static final long TOLERANCE_SECONDS = Duration.ofMinutes(2).toSeconds();
    // The live answers name the Badner Bahn after its operator, Wiener Lokalbahnen.
    private static final Map<String, String> TIMETABLE_NAMES = Map.of("WLB", "BB");

    private Matching() {}

    /// The live departure paired with each departure of `timetable`, in the same order, or `null`
    /// for the departures without one.
    static List<@Nullable LiveDeparture> pair(List<PlannedDeparture> timetable, List<LiveDeparture> live) {
        var timetableByLine = new HashMap<String, List<Integer>>();
        for (var i = 0; i < timetable.size(); i++) {
            timetableByLine
                    .computeIfAbsent(timetable.get(i).line(), any -> new ArrayList<>())
                    .add(i);
        }
        var liveByLine = new HashMap<String, List<Integer>>();
        for (var i = 0; i < live.size(); i++) {
            var line = live.get(i).line();
            liveByLine
                    .computeIfAbsent(TIMETABLE_NAMES.getOrDefault(line, line), any -> new ArrayList<>())
                    .add(i);
        }
        var paired = new ArrayList<@Nullable LiveDeparture>();
        timetable.forEach(any -> paired.add(null));
        liveByLine.forEach((line, liveIndexes) -> {
            var timetableIndexes = timetableByLine.getOrDefault(line, List.of());
            var same = pairs(timetable, timetableIndexes, live, liveIndexes, false);
            var reversed = pairs(timetable, timetableIndexes, live, liveIndexes, true);
            (reversed.size() > same.size() ? reversed : same)
                    .forEach((timetableIndex, liveIndex) -> paired.set(timetableIndex, live.get(liveIndex)));
        });
        return paired;
    }

    // The live departure index paired with each timetable departure index, closest planned times first,
    // each departure at most once.
    private static Map<Integer, Integer> pairs(
            List<PlannedDeparture> timetable,
            List<Integer> timetableIndexes,
            List<LiveDeparture> live,
            List<Integer> liveIndexes,
            boolean reversed) {
        var candidates = new ArrayList<Candidate>();
        for (var liveIndex : liveIndexes) {
            var departure = live.get(liveIndex);
            var direction = direction(departure.direction(), reversed);
            for (var timetableIndex : timetableIndexes) {
                var planned = timetable.get(timetableIndex);
                var gap = Math.abs(
                        Duration.between(planned.time(), departure.planned()).toSeconds());
                if (direction != null && direction.equals(planned.direction()) && gap <= TOLERANCE_SECONDS) {
                    candidates.add(new Candidate(gap, timetableIndex, liveIndex));
                }
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::gap)
                .thenComparingInt(Candidate::timetableIndex)
                .thenComparingInt(Candidate::liveIndex));
        var pairs = new HashMap<Integer, Integer>();
        var pairedLive = new HashSet<Integer>();
        for (var candidate : candidates) {
            if (!pairs.containsKey(candidate.timetableIndex()) && pairedLive.add(candidate.liveIndex())) {
                pairs.put(candidate.timetableIndex(), candidate.liveIndex());
            }
        }
        return pairs;
    }

    private static @Nullable Integer direction(String direction, boolean reversed) {
        return switch (direction) {
            case "H" -> reversed ? 1 : 0;
            case "R" -> reversed ? 0 : 1;
            default -> null;
        };
    }

    private record Candidate(long gap, int timetableIndex, int liveIndex) {}
}
