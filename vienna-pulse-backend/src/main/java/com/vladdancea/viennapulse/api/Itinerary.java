package com.vladdancea.viennapulse.api;

import java.util.ArrayList;
import java.util.List;

/// The part of a trip that a vehicle's path is worked out from: the moments it leaves and reaches
/// stops in `knots`, in order, and the points of its shape around them in `shape`, in order. Between
/// two knots the vehicle covers the distance at an even speed, so it waits at a stop from its arrival
/// until its departure, and it passes a leg the timetable gives no time at once.
record Itinerary(List<Knot> knots, List<ShapePoint> shape) {
    /// How far along its shape the trip is at `second`, counted on its service day like the stop times.
    record Knot(double second, double distance) {}

    /// A point of the shape and how far along the shape it is.
    record ShapePoint(double longitude, double latitude, double distance) {}

    /// The path from second `from` to `to`, or to the end of the trip if that comes first, with the
    /// seconds since `from`: where the vehicle is at the start, every point of the shape it passes,
    /// every stop it reaches or leaves, and where it is at the end. Points are placed on the straight
    /// line between the two points of the shape they fall between.
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
        return path;
    }

    // At two knots with the same second, the later one counts, so the trip has passed the leg.
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
