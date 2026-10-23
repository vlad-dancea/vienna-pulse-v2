import type { Feature, FeatureCollection, Point, Position } from 'geojson';
import type { GeoJSONSource } from 'maplibre-gl';
import type { VehicleProperties } from '../timetable/running-vehicles';

interface Glide {
  vehicle: Feature<Point, VehicleProperties>;
  from: Position;
}

/** Draws vehicles into a map source, moving each one at an even pace to its latest position. */
export class VehicleAnimation {
  private glides: Glide[] = [];
  private start = 0;
  private duration = 0;
  // Counts the updates, so a draw that started before one knows to draw again.
  private revision = 0;
  private drawing = false;
  private stopped = false;

  constructor(
    private readonly source: GeoJSONSource,
    private readonly window: Window,
  ) {}

  /**
   * Moves the vehicles from where they are drawn now to `vehicles` over `duration` milliseconds.
   * Vehicles not drawn yet appear where they are, and vehicles left out disappear.
   */
  show(vehicles: FeatureCollection<Point, VehicleProperties>, duration: number) {
    const now = this.window.performance.now();
    // After a long pause, such as a hidden tab, the drawn positions are minutes old, and racing
    // across that distance would look like a fault.
    const stale = now > this.start + 2 * this.duration;
    const drawn = new Map(stale ? [] : this.glides.map((glide) => [glide.vehicle.id, this.position(glide, now)]));
    this.glides = vehicles.features.map((vehicle) => ({
      vehicle,
      from: drawn.get(vehicle.id) ?? vehicle.geometry.coordinates,
    }));
    this.start = now;
    this.duration = duration;
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
          features: this.glides.map((glide) => ({
            ...glide.vehicle,
            geometry: { type: 'Point', coordinates: this.position(glide, now) },
          })),
        });
        const arrived = now >= this.start + this.duration;
        if (arrived && revision === this.revision) {
          return;
        }
        await new Promise((resolve) => this.window.requestAnimationFrame(resolve));
      }
    } finally {
      this.drawing = false;
    }
  }

  private position({ vehicle, from }: Glide, now: number): Position {
    const to = vehicle.geometry.coordinates;
    const share = this.duration > 0 ? Math.min(1, (now - this.start) / this.duration) : 1;
    return from.map((start, axis) => start + ((to[axis] ?? start) - start) * share);
  }
}
