import type { Feature, FeatureCollection, Point } from 'geojson';
import type { GeoJSONSource } from 'maplibre-gl';
import { VehicleAnimation } from './vehicle-animation';
import type { VehicleProperties } from '../timetable/running-vehicles';

function vehicle(id: string, longitude: number): Feature<Point, VehicleProperties> {
  return {
    type: 'Feature',
    id,
    geometry: { type: 'Point', coordinates: [longitude, 48.2] },
    properties: {
      line: id,
      name: id,
      longName: null,
      type: 'tram',
      color: null,
      textColor: null,
      headsign: 'Zentrum',
    },
  };
}

function vehicles(...features: Feature<Point, VehicleProperties>[]): FeatureCollection<Point, VehicleProperties> {
  return { type: 'FeatureCollection', features };
}

describe('VehicleAnimation', () => {
  let setData: ReturnType<typeof vi.fn<(data: FeatureCollection<Point, VehicleProperties>) => Promise<void>>>;
  let animation: VehicleAnimation;

  // Where each drawn vehicle was in the last frame, by id.
  const drawn = (): Partial<Record<'a' | 'b', number>> =>
    Object.fromEntries(
      (setData.mock.lastCall?.[0].features ?? []).map((feature) => [
        String(feature.id),
        feature.geometry.coordinates[0],
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

  it('glides from the drawn positions at an even pace, then stops drawing', async () => {
    animation.show(vehicles(vehicle('a', 16.3)), 15_000);
    await vi.advanceTimersByTimeAsync(0);
    expect(drawn()).toEqual({ a: 16.3 });

    await vi.advanceTimersByTimeAsync(1_000);
    animation.show(vehicles(vehicle('a', 16.5), vehicle('b', 16.4)), 10_000);
    await vi.advanceTimersByTimeAsync(5_000);
    // The vehicle drawn already is halfway, and the new one appears where it is.
    expect(drawn().a).toBeCloseTo(16.4, 2);
    expect(drawn().b).toBe(16.4);

    // A vehicle left out disappears at once.
    animation.show(vehicles(vehicle('b', 16.4)), 10_000);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(drawn()).toEqual({ b: 16.4 });
    const frames = setData.mock.calls.length;
    await vi.advanceTimersByTimeAsync(1_000);
    expect(setData).toHaveBeenCalledTimes(frames);
  });

  it('stops drawing when stopped with a frame pending', async () => {
    animation.show(vehicles(vehicle('a', 16.3)), 15_000);
    animation.show(vehicles(vehicle('a', 16.5)), 15_000);
    await vi.advanceTimersByTimeAsync(1_000);
    animation.stop();
    const frames = setData.mock.calls.length;
    await vi.advanceTimersByTimeAsync(1_000);
    expect(setData).toHaveBeenCalledTimes(frames);
  });

  it('jumps after a long pause instead of racing to the new positions', async () => {
    animation.show(vehicles(vehicle('a', 16.3)), 15_000);
    await vi.advanceTimersByTimeAsync(60_000);
    animation.show(vehicles(vehicle('a', 16.5)), 15_000);
    await vi.advanceTimersByTimeAsync(0);
    expect(drawn()).toEqual({ a: 16.5 });
  });

  it('draws the newest vehicles once without gliding when the duration is zero', async () => {
    animation.show(vehicles(vehicle('a', 16.3)), 0);
    animation.show(vehicles(vehicle('a', 16.5)), 0);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(setData.mock.calls.map(([data]) => data.features[0]?.geometry.coordinates[0])).toEqual([16.3, 16.5]);
  });
});
