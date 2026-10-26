import type { FeatureCollection, Point } from 'geojson';
import type { GeoJSONSource } from 'maplibre-gl';
import { VehicleAnimation } from './vehicle-animation';
import type { DrawnProperties } from './vehicle-animation';
import type { Vehicles } from '../timetable/running-vehicles';

type Path = [number, number, number][];

// An answer of the API at `seconds` after 08:00, with a vehicle for each id and path.
function vehicles(seconds: number, paths: Record<string, Path>): Vehicles {
  return {
    type: 'FeatureCollection',
    time: new Date(Date.UTC(2026, 9, 20, 6, 0, seconds)).toISOString(),
    features: Object.entries(paths).map(([id, path]) => ({
      type: 'Feature',
      id,
      geometry: { type: 'Point', coordinates: path[0]?.slice(0, 2) ?? [] },
      properties: {
        line: id,
        name: id,
        longName: null,
        type: 'tram',
        color: null,
        textColor: null,
        headsign: 'Zentrum',
        delay: null,
        path,
      },
    })),
  };
}

describe('VehicleAnimation', () => {
  let setData: ReturnType<typeof vi.fn<(data: FeatureCollection<Point, DrawnProperties>) => Promise<void>>>;
  let animation: VehicleAnimation;

  // Where each drawn vehicle was in the last frame, by id. Frames come every 16 ms, so the last one
  // can be a little earlier than the time a test waits for, and three decimals hide that.
  const drawn = (): Record<string, number[]> =>
    Object.fromEntries(
      (setData.mock.lastCall?.[0].features ?? []).map((feature) => [
        String(feature.id),
        feature.geometry.coordinates.map((coordinate) => Math.round(coordinate * 1e3) / 1e3),
      ]),
    );

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'performance'] });
    setData = vi.fn(() => Promise.resolve());
    animation = new VehicleAnimation({ setData } as unknown as GeoJSONSource, window);
  });

  afterEach(() => {
    animation.stop();
    vi.useRealTimers();
  });

  it('moves each vehicle along its path at the pace of its timetable, then stops drawing', async () => {
    // East for 10 seconds, waiting at a stop for 10, then north.
    const path: Path = [
      [16.3, 48.2, 0],
      [16.4, 48.2, 10],
      [16.4, 48.2, 20],
      [16.4, 48.3, 30],
    ];
    animation.show(vehicles(0, { a: path }), true);
    await vi.advanceTimersByTimeAsync(0);
    expect(drawn()).toEqual({ a: [16.3, 48.2] });
    expect(setData.mock.lastCall?.[0].features[0]?.properties).not.toHaveProperty('path');

    await vi.advanceTimersByTimeAsync(5_000);
    expect(drawn()).toEqual({ a: [16.35, 48.2] });
    await vi.advanceTimersByTimeAsync(10_000);
    expect(drawn()).toEqual({ a: [16.4, 48.2] });
    await vi.advanceTimersByTimeAsync(10_000);
    expect(drawn()).toEqual({ a: [16.4, 48.25] });

    await vi.advanceTimersByTimeAsync(10_000);
    expect(drawn()).toEqual({ a: [16.4, 48.3] });
    const frames = setData.mock.calls.length;
    await vi.advanceTimersByTimeAsync(1_000);
    expect(setData).toHaveBeenCalledTimes(frames);
  });

  it('goes on from where a vehicle is drawn until a newer answer starts, without a jump', async () => {
    animation.show(
      vehicles(0, {
        a: [
          [16.3, 48.2, 0],
          [16.6, 48.2, 30],
        ],
      }),
      true,
    );
    await vi.advanceTimersByTimeAsync(10_000);
    expect(drawn()).toEqual({ a: [16.4, 48.2] });

    // The answer for 08:00:15 arrives at 08:00:10 on the page's clock and puts the vehicle further
    // on than the earlier path, as after a new timetable. The vehicle goes there from where it is,
    // the vehicle left out disappears, and the new one waits where its path starts.
    animation.show(
      vehicles(15, {
        a: [
          [16.5, 48.2, 0],
          [16.8, 48.2, 30],
        ],
        b: [
          [16.5, 48.3, 0],
          [16.5, 48.4, 10],
        ],
      }),
      true,
    );
    await vi.advanceTimersByTimeAsync(1_000);
    expect(drawn()).toEqual({ a: [16.42, 48.2], b: [16.5, 48.3] });
    await vi.advanceTimersByTimeAsync(6_000);
    expect(drawn()).toEqual({ a: [16.52, 48.2], b: [16.5, 48.32] });
  });

  it('jumps to the newest positions when the paths ran out instead of racing there', async () => {
    animation.show(
      vehicles(0, {
        a: [
          [16.3, 48.2, 0],
          [16.6, 48.2, 30],
        ],
      }),
      true,
    );
    await vi.advanceTimersByTimeAsync(60_000);

    // The answer for 08:01:03 arrives at 08:01:00 on the page's clock, long after the paths ended.
    animation.show(
      vehicles(63, {
        a: [
          [16.9, 48.2, 0],
          [17.2, 48.2, 30],
        ],
      }),
      true,
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(drawn()).toEqual({ a: [16.9, 48.2] });
    await vi.advanceTimersByTimeAsync(10_000);
    expect(drawn()).toEqual({ a: [17, 48.2] });
  });

  it('draws each answer once, where the vehicles are, when it should not animate', async () => {
    animation.show(
      vehicles(0, {
        a: [
          [16.3, 48.2, 0],
          [16.6, 48.2, 30],
        ],
      }),
      false,
    );
    await vi.advanceTimersByTimeAsync(10_000);
    animation.show(
      vehicles(10, {
        a: [
          [16.4, 48.2, 0],
          [16.6, 48.2, 20],
        ],
      }),
      false,
    );
    await vi.advanceTimersByTimeAsync(10_000);
    expect(setData.mock.calls.map(([data]) => data.features[0]?.geometry.coordinates)).toEqual([
      [16.3, 48.2],
      [16.4, 48.2],
    ]);
  });

  it('stops drawing when stopped with a frame pending', async () => {
    animation.show(
      vehicles(0, {
        a: [
          [16.3, 48.2, 0],
          [16.6, 48.2, 30],
        ],
      }),
      true,
    );
    await vi.advanceTimersByTimeAsync(1_000);
    animation.stop();
    const frames = setData.mock.calls.length;
    await vi.advanceTimersByTimeAsync(1_000);
    expect(setData).toHaveBeenCalledTimes(frames);
  });
});
