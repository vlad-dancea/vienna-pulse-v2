package com.vladdancea.viennapulse.live;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// Pairs a station's timetable departures with Wiener Linien's live ones, which have no trip id.
///
/// A pair has the same line, the same direction and planned times at most two minutes apart, and
/// the closest pairs come first. Compared at five busy stations on 2026-10-24, Wiener Linien plan
/// some platforms a minute or two later than the timetable, such as tram 52's at Westbahnhof, and
/// their U-Bahn times have half minutes that the timetable rounds either way. A wider window would
/// pair guesses: at Hietzing on 2026-10-26 they planned tram 10 towards Unter St. Veit five minutes
/// after each of the timetable's trips, halfway to the next one.
///
/// A live departure's direction is the timetable's direction whose headsigns name its destination
/// better, so "Bhf. Heiligenstadt S U" goes the way of "Wien Heiligenstadt". Wiener Linien's own
/// directions, `H` and `R`, decide only where the names do not. They are the timetable's direction
/// ids 0 and 1 on most lines but the other way round on some, such as bus 5B, and even differ
/// between the variants of bus 3A, so each line reads them the way its named departures do, or
/// else the way that pairs more of its departures, and the usual one on a tie. Read by its pairs
/// alone, tram 10 at Hietzing came out the wrong way round on 2026-10-26, and so did the Badner Bahn
/// at Meidling, which leaves there both ways at the same minutes.
final class Matching {
    private static final long TOLERANCE_SECONDS = Duration.ofMinutes(2).toSeconds();
    // The live answers name the Badner Bahn after its operator, Wiener Lokalbahnen.
    private static final Map<String, String> TIMETABLE_NAMES = Map.of("WLB", "BB");
    private static final Pattern NOT_A_LETTER = Pattern.compile("\\P{L}+");
    // Starts most of the timetable's headsigns and says nothing about the destination.
    private static final String CITY = "wien";

    private Matching() {}

    /// The live departure paired with each departure of `timetable`, in the same order, or `null`
    /// for the departures without one.
    static List<@Nullable LiveDeparture> pair(List<PlannedDeparture> timetable, List<LiveDeparture> live) {
        var paired = new ArrayList<@Nullable LiveDeparture>();
        timetable.forEach(any -> paired.add(null));
        indexes(timetable, live)
                .forEach((timetableIndex, liveIndex) -> paired.set(timetableIndex, live.get(liveIndex)));
        return paired;
    }

    /// The timetable departure paired with each departure of `live`, in the same order, or `null` for
    /// the live departures without one.
    static List<@Nullable PlannedDeparture> planned(List<PlannedDeparture> timetable, List<LiveDeparture> live) {
        var paired = new ArrayList<@Nullable PlannedDeparture>();
        live.forEach(any -> paired.add(null));
        indexes(timetable, live)
                .forEach((timetableIndex, liveIndex) -> paired.set(liveIndex, timetable.get(timetableIndex)));
        return paired;
    }

    // The live departure index paired with each timetable departure index.
    private static Map<Integer, Integer> indexes(List<PlannedDeparture> timetable, List<LiveDeparture> live) {
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
        var indexes = new HashMap<Integer, Integer>();
        liveByLine.forEach((line, liveIndexes) -> {
            var timetableIndexes = timetableByLine.getOrDefault(line, List.of());
            var named = named(timetable, timetableIndexes, live, liveIndexes);
            var usual = 0;
            var reversed = 0;
            for (var entry : named.entrySet()) {
                var direction = live.get(entry.getKey()).direction();
                if (entry.getValue().equals(direction(direction, false))) {
                    usual++;
                } else if (entry.getValue().equals(direction(direction, true))) {
                    reversed++;
                }
            }
            IntFunction<@Nullable Integer> same = i -> direction(named, live, i, false);
            IntFunction<@Nullable Integer> other = i -> direction(named, live, i, true);
            if (usual != reversed) {
                indexes.putAll(pairs(timetable, timetableIndexes, live, liveIndexes, reversed > usual ? other : same));
            } else {
                var samePairs = pairs(timetable, timetableIndexes, live, liveIndexes, same);
                var otherPairs = pairs(timetable, timetableIndexes, live, liveIndexes, other);
                indexes.putAll(otherPairs.size() > samePairs.size() ? otherPairs : samePairs);
            }
        });
        return indexes;
    }

    // The direction id that the headsigns of a line's timetable departures give each of its live
    // departures whose destination agrees better with the headsigns of one direction than with those
    // of the other.
    private static Map<Integer, Integer> named(
            List<PlannedDeparture> timetable,
            List<Integer> timetableIndexes,
            List<LiveDeparture> live,
            List<Integer> liveIndexes) {
        var headsigns = new HashMap<Integer, Set<List<String>>>();
        for (var timetableIndex : timetableIndexes) {
            var planned = timetable.get(timetableIndex);
            var direction = planned.direction();
            if (direction != null) {
                headsigns.computeIfAbsent(direction, any -> new HashSet<>()).add(words(planned.headsign()));
            }
        }
        var named = new HashMap<Integer, Integer>();
        for (var liveIndex : liveIndexes) {
            var destination = words(live.get(liveIndex).towards());
            var first = agreement(destination, headsigns.getOrDefault(0, Set.of()));
            var second = agreement(destination, headsigns.getOrDefault(1, Set.of()));
            if (first != second) {
                named.put(liveIndex, first > second ? 0 : 1);
            }
        }
        return named;
    }

    // How many words `destination` shares with the one of `headsigns` that agrees with it best. A
    // destination and a headsign agree when all the words of one are in the other, as with
    // "Bhf. Heiligenstadt S U" and "Wien Heiligenstadt", so that a word like the Platz of
    // Friedrich-Engels-Platz and Lorenz-Bayer-Platz decides nothing alone. A word of the destination
    // is in the headsign when a word of the headsign equals it or starts with it, as with Wiener
    // Linien's "Baden Josefspl.".
    private static int agreement(List<String> destination, Set<List<String>> headsigns) {
        var most = 0;
        for (var headsign : headsigns) {
            var shared = (int) destination.stream()
                    .filter(word -> headsign.stream().anyMatch(other -> other.startsWith(word)))
                    .count();
            var covered =
                    headsign.stream().allMatch(other -> destination.stream().anyMatch(other::startsWith));
            if (!headsign.isEmpty() && (shared == destination.size() || covered)) {
                most = Math.max(most, shared);
            }
        }
        return most;
    }

    // The words of a place's name that can tell two destinations apart, in lower case. Shorter ones
    // are mostly marks, such as the U and S of the stations with U-Bahn and S-Bahn.
    private static List<String> words(@Nullable String name) {
        if (name == null) {
            return List.of();
        }
        return NOT_A_LETTER
                .splitAsStream(name.toLowerCase(Locale.ROOT))
                .filter(word -> word.length() >= 3 && !word.equals(CITY))
                .distinct()
                .toList();
    }

    // The live departure index paired with each timetable departure index of one line, closest planned
    // times first, each departure at most once.
    private static Map<Integer, Integer> pairs(
            List<PlannedDeparture> timetable,
            List<Integer> timetableIndexes,
            List<LiveDeparture> live,
            List<Integer> liveIndexes,
            IntFunction<@Nullable Integer> directions) {
        var candidates = new ArrayList<Candidate>();
        for (var liveIndex : liveIndexes) {
            var departure = live.get(liveIndex);
            var direction = directions.apply(liveIndex);
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

    // The direction id of the live departure with the index `liveIndex`: the one its destination names,
    // or else the one its `H` or `R` has in the reading.
    private static @Nullable Integer direction(
            Map<Integer, Integer> named, List<LiveDeparture> live, int liveIndex, boolean reversed) {
        var direction = named.get(liveIndex);
        return direction != null ? direction : direction(live.get(liveIndex).direction(), reversed);
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
