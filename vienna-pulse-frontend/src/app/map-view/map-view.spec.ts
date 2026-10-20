import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MapView } from './map-view';
import { featureFilter } from '@maplibre/maplibre-gl-style-spec';
import type { FilterSpecification } from 'maplibre-gl';
import { NetworkStops } from './network-stops';

const map = vi.hoisted(() => ({
  once: vi.fn(),
  addSource: vi.fn(),
  addLayer: vi.fn(),
  getSource: vi.fn(),
  setFilter: vi.fn(),
  remove: vi.fn(),
}));

vi.mock('maplibre-gl', () => ({
  MapLibreMap: vi.fn(function () {
    return map;
  }),
  setWorkerUrl: vi.fn(),
}));

const routes = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      id: 'tram-62',
      geometry: {
        type: 'MultiLineString',
        coordinates: [
          [
            [16.3, 48.2],
            [16.4, 48.2],
          ],
        ],
      },
      properties: { name: '62', longName: null, type: 'tram', color: null, textColor: null },
    },
  ],
};

const stops = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      id: 'subway',
      geometry: { type: 'Point', coordinates: [16.37, 48.2] },
      properties: { name: 'Subway', modes: ['subway'] },
    },
    {
      type: 'Feature',
      id: 'tram',
      geometry: { type: 'Point', coordinates: [16.38, 48.2] },
      properties: { name: 'Tram', modes: ['tram'] },
    },
    {
      type: 'Feature',
      id: 'bus',
      geometry: { type: 'Point', coordinates: [16.39, 48.2] },
      properties: { name: 'Bus', modes: ['bus'] },
    },
    {
      type: 'Feature',
      id: 'interchange',
      geometry: { type: 'Point', coordinates: [16.4, 48.2] },
      properties: { name: 'Interchange', modes: ['bus', 'subway', 'tram'] },
    },
    {
      type: 'Feature',
      id: 'unused',
      geometry: { type: 'Point', coordinates: [16.41, 48.2] },
      properties: { name: 'Unused', modes: [] },
    },
  ],
};

describe('MapView', () => {
  let http: HttpTestingController;
  let loadMap: () => void;
  let setData: ReturnType<typeof vi.fn>;
  let setStops: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    map.once.mockImplementation((_event: string, callback: () => void) => {
      loadMap = callback;
    });
    setData = vi.fn();
    setStops = vi.fn();
    map.getSource.mockImplementation((source: string) => ({ setData: source === 'stops' ? setStops : setData }));
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it.each([
    ['Tram', 'tram'],
    ['U-Bahn', 'subway'],
    ['Bus', 'bus'],
  ])('applies %s before the map loads, then restores all routes without refetching', async (label, type) => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    const element = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(element.querySelectorAll('button'));
    const selected = buttons.find((button) => button.textContent.trim() === label);
    selected?.click();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/stops').flush(stops);
    await fixture.whenStable();
    expect(setData).not.toHaveBeenCalled();

    loadMap();
    await fixture.whenStable();
    expect(setData).toHaveBeenLastCalledWith(routes);
    for (const layer of ['lines-casing', 'lines', 'line-names']) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, ['==', ['get', 'type'], type]);
    }
    expect(selected?.getAttribute('aria-pressed')).toBe('true');
    expect(setStops).toHaveBeenLastCalledWith(stops);
    expect(TestBed.inject(NetworkStops).classified()).toBe(true);
    expect(map.setFilter).toHaveBeenCalledWith('stops', ['in', type, ['get', 'modes']]);
    const stopFilter = map.setFilter.mock.calls
      .filter(([layer]: unknown[]) => layer === 'stops')
      .at(-1)?.[1] as FilterSpecification;
    const evaluate = featureFilter(stopFilter, 'filter').filter;
    expect(
      stops.features
        .filter((stop) => evaluate({ zoom: 12 }, { type: 1, properties: stop.properties }))
        .map((stop) => stop.id),
    ).toEqual([type, 'interchange']);

    buttons.find((button) => button.textContent.trim() === 'All')?.click();
    await fixture.whenStable();
    expect(selected?.getAttribute('aria-pressed')).toBe('false');
    for (const layer of ['lines-casing', 'lines', 'line-names']) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, null);
    }
    expect(map.setFilter).toHaveBeenLastCalledWith('stops', null);
    http.expectNone((request) => request.url === '/api/lines');
    http.expectNone('/api/stops');
    fixture.destroy();
    expect(map.remove).toHaveBeenCalledOnce();
  });

  it('populates an already loaded map when the routes arrive', async () => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain("Loading today's routes...");
    loadMap();
    TestBed.tick();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/stops').flush(stops);
    await fixture.whenStable();
    expect(setData).toHaveBeenLastCalledWith(routes);
    expect(element.textContent).toContain("Routes follow today's timetable");
  });

  it.each([
    [404, "Today's routes are not available yet."],
    [503, 'Routes could not be loaded.'],
  ])('explains HTTP %s and retries without losing keyboard focus', async (status, message) => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush({}, { status, statusText: 'Unavailable' });
    http.expectOne('/api/stops').flush(stops);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role="status"]')?.textContent).toContain(message);
    const retry = Array.from(element.querySelectorAll('button')).find((button) => button.textContent.includes('Retry'));
    retry?.focus();
    retry?.click();
    TestBed.tick();
    http.expectOne((request) => request.url === '/api/lines').flush({ type: 'FeatureCollection', features: [] });
    await fixture.whenStable();
    expect(element.textContent).toContain('No routes are scheduled for today.');
    expect(setData).toHaveBeenLastCalledWith({ type: 'FeatureCollection', features: [] });
    expect(document.activeElement).toBe(element.querySelector('[role="status"]'));
  });
  it.each([undefined, null])(
    'keeps stops visible against unclassified API modes %s, then applies the selected mode',
    async (modes) => {
      const fixture = TestBed.createComponent(MapView);
      TestBed.tick();
      loadMap();
      http.expectOne((request) => request.url === '/api/lines').flush(routes);
      http.expectOne('/api/stops').flush({
        ...stops,
        features: stops.features.map((stop) => ({ ...stop, properties: { name: stop.properties.name, modes } })),
      });
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;
      const subway = Array.from(element.querySelectorAll('button')).find(
        (button) => button.textContent.trim() === 'U-Bahn',
      );
      subway?.click();
      await fixture.whenStable();
      expect(map.setFilter).toHaveBeenLastCalledWith('stops', null);
      expect(element.textContent).toContain('Stop filters are updating. All stops are shown for now.');
      TestBed.inject(NetworkStops).stops.reload();
      TestBed.tick();
      http.expectOne('/api/stops').flush(stops);
      await fixture.whenStable();
      expect(map.setFilter).toHaveBeenLastCalledWith('stops', ['in', 'subway', ['get', 'modes']]);
      expect(element.textContent).not.toContain('Stop filters are updating');
      fixture.destroy();
    },
  );

  it('retries failed stops while keeping keyboard focus on their status', async () => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/stops').flush({}, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const retry = Array.from(element.querySelectorAll('button')).find(
      (button) => button.textContent.trim() === 'Retry stops',
    );
    retry?.focus();
    retry?.click();
    TestBed.tick();
    http.expectOne('/api/stops').flush(stops);
    await fixture.whenStable();
    expect(document.activeElement?.textContent).toContain('Stops match the selected mode');
    expect(element.textContent).not.toContain('Stops could not be loaded');
    fixture.destroy();
  });
});
