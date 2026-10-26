package com.vladdancea.viennapulse.api;

import java.util.ArrayList;
import java.util.List;

/// How many seconds a vehicle runs behind its timetable over time, negative when ahead of it: the
/// delay at each of `points`, in the order of their seconds. Between two points it changes evenly,
/// and before the first and after the last it stays as it is.
record Delay(List<Point> points) {
    static final Delay NONE = new Delay(List.of());

    /// The delay at `second`.
    double at(double second) {
        if (points.isEmpty() || second <= points.getFirst().second()) {
            return points.isEmpty() ? 0 : points.getFirst().delay();
        }
        for (var index = 1; index < points.size(); index++) {
            var next = points.get(index);
            if (next.second() > second) {
                var last = points.get(index - 1);
                var share = (second - last.second()) / (next.second() - last.second());
                return last.delay() + share * (next.delay() - last.delay());
            }
        }
        return points.getLast().delay();
    }

    /// This delay until `start`, and from then on one that changes by `rate` seconds each second
    /// until it is `target`.
    Delay towards(double target, double start, double rate) {
        var kept = new ArrayList<Point>();
        for (var point : points) {
            if (point.second() < start) {
                kept.add(point);
            }
        }
        var delay = at(start);
        kept.add(new Point(start, delay));
        if (target != delay) {
            kept.add(new Point(start + Math.abs(target - delay) / rate, target));
        }
        return new Delay(kept);
    }

    /// This delay from second `from` to `to`, with its seconds counted from `from`.
    Delay between(double from, double to) {
        var between = new ArrayList<Point>();
        between.add(new Point(0, at(from)));
        for (var point : points) {
            if (point.second() > from && point.second() < to) {
                between.add(new Point(point.second() - from, point.delay()));
            }
        }
        between.add(new Point(to - from, at(to)));
        return new Delay(between);
    }

    /// This delay from `second` on, which is all that [#at] reads from then on.
    Delay since(double second) {
        if (points.isEmpty()) {
            return this;
        }
        var since = new ArrayList<Point>();
        since.add(new Point(second, at(second)));
        for (var point : points) {
            if (point.second() > second) {
                since.add(point);
            }
        }
        return new Delay(since);
    }

    /// The delay after the last point.
    double last() {
        return points.isEmpty() ? 0 : points.getLast().delay();
    }

    /// Whether the vehicle keeps to its timetable throughout.
    boolean none() {
        return points.stream().allMatch(point -> point.delay() == 0);
    }

    /// The `second` at which a vehicle runs `delay` seconds behind its timetable.
    record Point(double second, double delay) {}
}
