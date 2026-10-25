import type { Feature, Point } from 'geojson';
import type { GeoJSONSource } from 'maplibre-gl';
import type { VehicleProperties, Vehicles } from '../timetable/running-vehicles';

/** What the map draws for a vehicle: everything the API sends but its path. */
export type DrawnProperties = Omit<VehicleProperties, 'path'>;

/** Where a vehicle is at a time of the page's clock, `performance.now()`. */
type Waypoint = [time: number, longitude: number, latitude: number];

type Place = [longitude: number, latitude: number];

interface Moving {
  vehicle: Feature<Point, DrawnProperties>;
  waypoints: Waypoint[];
}

/** Draws vehicles into a map source, moving each one along the path the latest answer gives it. */
export class VehicleAnimation {
  private moving: Moving[] = [];
  // Turns a time of the API into one of the page's clock. It is set from the first answer, so the
  // vehicles start where their paths start, and kept, so the next answers continue where they are.
  private shift: number | undefined;
  // When the paths of the latest answer end on the page's clock, after which nothing moves.
  private end = 0;
  private animate = true;
  // Counts the answers, so a draw that started before one knows to draw again.
  private revision = 0;
  private drawing = false;
  private stopped = false;

  constructor(
    private readonly source: GeoJSONSource,
    private readonly window: Window,
  ) {}

  /**
   * Moves each of `vehicles` along its path from where it is now, or puts it there at once and keeps
   * it there unless it should `animate`. Vehicles left out disappear.
   */
  show(vehicles: Vehicles, animate: boolean) {
    const now = this.window.performance.now();
    const time = Date.parse(vehicles.time);
    // Paths that ended before now, such as those of a tab hidden for a while, or an answer far from
    // the page's clock, such as after the computer slept, would leave the vehicles racing across the
    // gap. Starting the clock again makes them jump to where they are instead.
    const shift = this.shift;
    const keep = shift !== undefined && now <= this.end && Math.abs(time + shift - now) <= 10_000;
    this.shift = keep ? shift : now - time;
    const start = time + this.shift;
    const earlier = new Map(keep ? this.moving.map(({ vehicle, waypoints }) => [vehicle.id, waypoints]) : []);
    this.moving = vehicles.features.map(({ properties: { path, ...properties }, ...vehicle }) => {
      // An answer can start a few seconds after now. Until then the vehicle goes on from where it is
      // drawn along the earlier path, which meets the answer's path unless the timetable changed.
      const before = ahead(earlier.get(vehicle.id) ?? [], now);
      const here = now < start ? position(before, now) : undefined;
      const bridge: Waypoint[] = here
        ? [[now, ...here], ...before.filter(([waypoint]) => waypoint > now && waypoint < start)]
        : [];
      return {
        vehicle: { ...vehicle, properties },
        waypoints: [
          ...bridge,
          ...path.map(([longitude, latitude, seconds]): Waypoint => [start + seconds * 1000, longitude, latitude]),
        ],
      };
    });
    this.end = Math.max(start, ...this.moving.map(({ waypoints }) => waypoints.at(-1)?.[0] ?? start));
    this.animate = animate;
    this.revision++;
    void this.draw();
  }

  /** Stops drawing, for when the map goes away. */
  stop() {
    this.stopped = true;
  }

  // Draws one frame at a time, and only after MapLibre has taken the previous one, so a slow
  // device draws fewer frames instead of queueing them.
  private async draw() {
    if (this.drawing) {
      return;
    }
    this.drawing = true;
    try {
      // The map can go away while a frame is pending, so check before every write.
      while (!this.stopped) {
        const now = this.window.performance.now();
        const revision = this.revision;
        await this.source.setData({
          type: 'FeatureCollection',
          features: this.moving.map(({ vehicle, waypoints }) => ({
            ...vehicle,
            geometry: { type: 'Point', coordinates: position(waypoints, now) ?? vehicle.geometry.coordinates },
          })),
        });
        const still = !this.animate || now >= this.end;
        if (still && revision === this.revision) {
          return;
        }
        await new Promise((resolve) => this.window.requestAnimationFrame(resolve));
      }
    } finally {
      this.drawing = false;
    }
  }
}

// The waypoints from the last one at or before `now` on, which are all that `position` reads from now on.
function ahead(waypoints: Waypoint[], now: number): Waypoint[] {
  let first = 0;
  while ((waypoints[first + 1]?.[0] ?? Infinity) <= now) {
    first++;
  }
  return waypoints.slice(first);
}

// Where a vehicle is at `now`, on the straight line between the waypoints around it. Of two
// waypoints at the same time, the later one counts.
function position(waypoints: Waypoint[], now: number): Place | undefined {
  const [last, next] = ahead(waypoints, now);
  if (!last) {
    return undefined;
  }
  const [time, longitude, latitude] = last;
  if (!next || now <= time) {
    return [longitude, latitude];
  }
  const share = (now - time) / (next[0] - time);
  return [longitude + (next[1] - longitude) * share, latitude + (next[2] - latitude) * share];
}
