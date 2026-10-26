package com.vladdancea.viennapulse.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// The part of a trip that a vehicle's path is worked out from: the moments it leaves and reaches
/// stops in `knots`, in order, and the points of its shape around them in `shape`, in order. Between
/// two knots the vehicle covers the distance at an even speed, so it waits at a stop from its arrival
/// until its departure. The knots must include every call at the times of the first and the last.
record Itinerary(List<Knot> knots, List<ShapePoint> shape) {
    private static final double EARTH_RADIUS_METRES = 6_371_000;

    // The timetable gives whole minutes, so two stops a few hundred metres apart can share one, and
    // the vehicle would jump from one to the other. Instead it is halfway between them at that minute
    // and covers the rest of the way in the legs before and after. Every call at that minute has to be
    // among the knots, or two answers would put the vehicle in different places.
    Itinerary {
        var shared = new ArrayList<Knot>();
        for (var first = 0; first < knots.size(); ) {
            var last = first;
            while (last + 1 < knots.size()
                    && knots.get(last + 1).second() == knots.get(first).second()) {
                last++;
            }
            var second = knots.get(first).second();
            shared.add(new Knot(
                    second, (knots.get(first).distance() + knots.get(last).distance()) / 2));
            first = last + 1;
        }
        knots = shared;
        // A few shapes give two points up to 108 m apart the same distance, which no distance places
        // the vehicle between, so it would jump. Keeping only the last of them makes it go straight.
        var points = new ArrayList<ShapePoint>();
        for (var point : shape) {
            if (!points.isEmpty() && points.getLast().distance() == point.distance()) {
                points.removeLast();
            }
            points.add(point);
        }
        shape = points;
    }

    /// How far along its shape the trip is at `second`, counted on its service day like the stop times.
    record Knot(double second, double distance) {}

    /// A point of the shape and how far along the shape it is.
    record ShapePoint(double longitude, double latitude, double distance) {}

    /// This itinerary as a vehicle follows it that runs `delay` behind it, with the seconds of `delay`
    /// counted from `from`. The delay must change by less than a second each second, so that the
    /// vehicle never turns back. Where it changes how fast it changes, the vehicle changes speed, so
    /// those moments become knots too.
    Itinerary delayed(double from, Delay delay) {
        var points = delay.points();
        var delayed = new ArrayList<Knot>();
        var point = 0;
        for (var knot : knots) {
            var second = from + reached(knot.second() - from, points);
            for (; point < points.size() && from + points.get(point).second() < second; point++) {
                bend(from, points.get(point)).ifPresent(delayed::add);
            }
            delayed.add(new Knot(second, knot.distance()));
        }
        for (; point < points.size(); point++) {
            bend(from, points.get(point)).ifPresent(delayed::add);
        }
        return new Itinerary(delayed, shape);
    }

    // Where the vehicle is at a point of its delay, unless the trip has not started or has ended then.
    private Optional<Knot> bend(double from, Delay.Point point) {
        var second = from + point.second() - point.delay();
        return second > knots.getFirst().second() && second < knots.getLast().second()
                ? Optional.of(new Knot(from + point.second(), distance(second)))
                : Optional.empty();
    }

    // The second since `from` at which a vehicle `points` behind this itinerary reaches where it is
    // `seconds` after `from`. Each point takes the vehicle to where this itinerary was its delay
    // before, and so does each second between two, since the delay changes evenly.
    private static double reached(double seconds, List<Delay.Point> points) {
        if (points.isEmpty()) {
            return seconds;
        }
        var first = points.getFirst();
        if (seconds <= first.second() - first.delay()) {
            return seconds + first.delay();
        }
        for (var index = 1; index < points.size(); index++) {
            var next = points.get(index);
            var nextPlace = next.second() - next.delay();
            if (nextPlace > seconds) {
                var last = points.get(index - 1);
                var lastPlace = last.second() - last.delay();
                return last.second()
                        + (seconds - lastPlace) * (next.second() - last.second()) / (nextPlace - lastPlace);
            }
        }
        return seconds + points.getLast().delay();
    }

    /// The path from second `from` to `to`, or to the end of the trip if that comes first, with the
    /// seconds since `from`: where the vehicle is at the start, every point of the shape it passes,
    /// every stop it reaches or leaves, and where it is at the end. Points are placed on the straight
    /// line between the two points of the shape they fall between, and points that the path would
    /// pass within a metre of anyway are left out.
    List<Waypoint> path(double from, double to) {
        var end = Math.min(to, knots.getLast().second());
        var second = from;
        var distance = distance(from);
        var path = new ArrayList<Waypoint>();
        path.add(at(distance, 0));
        var point = 0;
        for (var knot : knots) {
            if (second >= end) {
                break;
            }
            if (knot.second() <= from) {
                continue;
            }
            var nextSecond = Math.min(knot.second(), end);
            var nextDistance = knot.second() <= end ? knot.distance() : distance(end);
            for (; point < shape.size() && shape.get(point).distance() < nextDistance; point++) {
                var passed = shape.get(point);
                if (passed.distance() > distance && nextSecond > second) {
                    var share = (passed.distance() - distance) / (nextDistance - distance);
                    path.add(waypoint(
                            passed.longitude(), passed.latitude(), second + share * (nextSecond - second) - from));
                }
            }
            if (nextSecond > second || nextDistance != distance) {
                path.add(at(nextDistance, nextSecond - from));
            }
            second = nextSecond;
            distance = nextDistance;
        }
        var kept = new boolean[path.size()];
        kept[0] = true;
        kept[path.size() - 1] = true;
        keep(path, kept, 0, path.size() - 1);
        var simplified = new ArrayList<Waypoint>();
        for (var index = 0; index < path.size(); index++) {
            if (kept[index]) {
                simplified.add(path.get(index));
            }
        }
        return simplified;
    }

    // Keeps the point between `first` and `last` that is farthest from where the straight line between
    // them puts the vehicle at the point's second, if it is over a metre away, then does the same on
    // both sides of it, as Douglas and Peucker simplify lines with time as a third axis. This leaves out
    // the points of straight streets driven at an even speed and makes the answer at the evening peak a
    // quarter smaller, while a metre is less than a pixel at street zoom.
    private static void keep(List<Waypoint> path, boolean[] kept, int first, int last) {
        var start = path.get(first);
        var end = path.get(last);
        var farthest = -1;
        var farthestMetres = 1.0;
        for (var index = first + 1; index < last; index++) {
            var point = path.get(index);
            var share = end.seconds() > start.seconds()
                    ? (point.seconds() - start.seconds()) / (end.seconds() - start.seconds())
                    : 0;
            var metres = metres(
                    point,
                    start.longitude() + (end.longitude() - start.longitude()) * share,
                    start.latitude() + (end.latitude() - start.latitude()) * share);
            if (metres > farthestMetres) {
                farthest = index;
                farthestMetres = metres;
            }
        }
        if (farthest != -1) {
            kept[farthest] = true;
            keep(path, kept, first, farthest);
            keep(path, kept, farthest, last);
        }
    }

    // Over the few hundred metres of a path, the earth is flat enough to measure on.
    private static double metres(Waypoint point, double longitude, double latitude) {
        var metresPerDegree = Math.toRadians(EARTH_RADIUS_METRES);
        var east = (point.longitude() - longitude) * metresPerDegree * Math.cos(Math.toRadians(latitude));
        var north = (point.latitude() - latitude) * metresPerDegree;
        return Math.hypot(east, north);
    }

    private double distance(double second) {
        for (var index = 1; index < knots.size(); index++) {
            var next = knots.get(index);
            if (next.second() > second) {
                var last = knots.get(index - 1);
                var share = Math.clamp((second - last.second()) / (next.second() - last.second()), 0, 1);
                return last.distance() + share * (next.distance() - last.distance());
            }
        }
        return knots.getLast().distance();
    }

    private Waypoint at(double distance, double seconds) {
        var before = shape.getFirst();
        var after = before;
        for (var point : shape) {
            after = point;
            if (point.distance() > distance) {
                break;
            }
            before = point;
        }
        var share = after.distance() > before.distance()
                ? Math.clamp((distance - before.distance()) / (after.distance() - before.distance()), 0, 1)
                : 0;
        return waypoint(
                before.longitude() + (after.longitude() - before.longitude()) * share,
                before.latitude() + (after.latitude() - before.latitude()) * share,
                seconds);
    }

    // Rounded like the lines, to six decimals (about 10 cm), and to a tenth of a second.
    private static Waypoint waypoint(double longitude, double latitude, double seconds) {
        return new Waypoint(
                Math.round(longitude * 1e6) / 1e6, Math.round(latitude * 1e6) / 1e6, Math.round(seconds * 10) / 10.0);
    }
}
