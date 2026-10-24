import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NavigationEnd, provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { filter, firstValueFrom } from 'rxjs';
import { RouterTestingHarness } from '@angular/router/testing';
import type { FilterSpecification, LayerSpecification, LineLayerSpecification } from 'maplibre-gl';
import { MapView } from './map-view';
import { withIds } from './stops';
import type { components } from '../timetable/openapi';
import { featureFilter } from '@maplibre/maplibre-gl-style-spec';

const map = vi.hoisted(() => ({
  on: vi.fn(),
  once: vi.fn(),
  addSource: vi.fn(),
  addLayer: vi.fn<(layer: LayerSpecification, beforeId?: string) => void>(),
  getStyle: vi.fn<() => { layers: LayerSpecification[] }>(() => ({ layers: [] })),
  getSource: vi.fn(),
  setFilter: vi.fn(),
  remove: vi.fn(),
  getCanvas: vi.fn(),
  getContainer: vi.fn(),
  getZoom: vi.fn(() => 12),
  queryRenderedFeatures: vi.fn(),
  project: vi.fn(),
  easeTo: vi.fn(),
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

const stops: components['schemas']['FeatureCollectionPointStopProperties'] = {
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

const vehicles = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      id: 'trip-1',
      geometry: { type: 'Point', coordinates: [16.35, 48.2] },
      properties: {
        line: 'tram-62',
        name: '62',
        longName: null,
        type: 'tram',
        color: null,
        textColor: null,
        headsign: 'Oper',
      },
    },
  ],
};

const noVehicles = { type: 'FeatureCollection', features: [] };

const busFilter: FilterSpecification = ['!', ['in', ['get', 'type'], ['literal', ['tram', 'subway']]]];
const expectedLayers: [string, FilterSpecification][] = [
  ['lines-bus-casing', busFilter],
  ['lines-bus', busFilter],
  ['lines-tram-casing', ['==', ['get', 'type'], 'tram']],
  ['lines-tram', ['==', ['get', 'type'], 'tram']],
  ['lines-subway-casing', ['==', ['get', 'type'], 'subway']],
  ['lines-subway', ['==', ['get', 'type'], 'subway']],
];

describe('MapView', () => {
  let http: HttpTestingController;
  let loadMap: () => void;
  let loadStyle: () => void;
  let errorListeners: Set<(event: { error: Error }) => void>;
  let listeners: Map<string, (event: unknown) => void>;
  let canvas: HTMLCanvasElement;
  let setData: ReturnType<typeof vi.fn>;
  let setStops: ReturnType<typeof vi.fn>;
  let setVehicles: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    map.once.mockImplementation((event: string, callback: () => void) => {
      if (event === 'load') {
        loadMap = callback;
      } else {
        loadStyle = callback;
      }
    });
    errorListeners = new Set();
    listeners = new Map();
    map.on.mockImplementation((event: string, listener: (event: { error: Error }) => void) => {
      if (event !== 'error') {
        listeners.set(event, listener as (event: unknown) => void);
        return;
      }
      errorListeners.add(listener);
      return { unsubscribe: () => errorListeners.delete(listener) };
    });
    canvas = document.createElement('canvas');
    canvas.tabIndex = 0;
    document.body.append(canvas);
    map.getCanvas.mockReturnValue(canvas);
    map.getContainer.mockReturnValue(document.body);
    setData = vi.fn();
    setStops = vi.fn();
    setVehicles = vi.fn();
    map.getSource.mockImplementation((source: string) => ({
      setData: source === 'stops' ? setStops : source === 'vehicles' ? setVehicles : setData,
    }));
    // jsdom has no media queries. Reduced motion draws the vehicles once, where they are.
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => ({ matches: true }) });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '', component: MapView }], withComponentInputBinding()),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    canvas.remove();
    vi.restoreAllMocks();
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
    http.expectOne('/api/vehicles').flush(noVehicles);
    await fixture.whenStable();
    expect(setData).not.toHaveBeenCalled();

    loadMap();
    await fixture.whenStable();
    expect(setData).toHaveBeenLastCalledWith(routes);
    for (const [layer, filter] of expectedLayers) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, ['all', filter, ['==', ['get', 'type'], type]]);
    }
    expect(map.setFilter).toHaveBeenCalledWith('line-names', ['==', ['get', 'type'], type]);
    expect(map.setFilter).toHaveBeenCalledWith('vehicles', ['==', ['get', 'type'], type]);
    expect(selected?.getAttribute('aria-pressed')).toBe('true');
    expect(setStops).toHaveBeenLastCalledWith(withIds(stops));
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

    map.setFilter.mockClear();
    buttons.find((button) => button.textContent.trim() === 'All')?.click();
    await fixture.whenStable();
    expect(selected?.getAttribute('aria-pressed')).toBe('false');
    for (const [layer, filter] of expectedLayers) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, filter);
    }
    expect(map.setFilter).toHaveBeenLastCalledWith('stops', null);
    expect(map.setFilter).toHaveBeenCalledWith('line-names', null);
    expect(map.setFilter).toHaveBeenCalledWith('vehicles', null);
    http.expectNone((request) => request.url === '/api/lines');
    http.expectNone('/api/stops');
    fixture.destroy();
    expect(map.remove).toHaveBeenCalledOnce();
  });

  it('populates an already loaded map when the routes arrive', async () => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    TestBed.tick();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/stops').flush(stops);
    http.expectOne('/api/vehicles').flush(noVehicles);
    await fixture.whenStable();
    expect(setData).toHaveBeenLastCalledWith(routes);
    const lineLayers = map.addLayer.mock.calls
      .map(([layer]) => layer)
      .filter((layer): layer is LineLayerSpecification => layer.type === 'line' && layer.source === 'lines');
    expect(lineLayers.map((layer) => [layer.id, layer.filter])).toEqual(expectedLayers);
    expect(map.addLayer.mock.calls.map(([layer]) => layer.id)).toEqual([
      ...expectedLayers.map(([id]) => id),
      'stops',
      'selected-stop',
      'line-names',
      'vehicles',
      'district-boundaries',
      'district-names',
    ]);
  });

  it('draws the running vehicles and keeps them when a refresh fails', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const fixture = TestBed.createComponent(MapView);
    try {
      TestBed.tick();
      loadMap();
      TestBed.tick();
      http.expectOne((request) => request.url === '/api/lines').flush(routes);
      http.expectOne('/api/stops').flush(stops);
      http.expectOne('/api/vehicles').flush(vehicles);
      await fixture.whenStable();
      expect(setVehicles).toHaveBeenLastCalledWith(vehicles);

      vi.advanceTimersByTime(15_000);
      TestBed.tick();
      http.expectOne('/api/vehicles').flush({}, { status: 503, statusText: 'Unavailable' });
      await fixture.whenStable();
      expect(setVehicles).toHaveBeenLastCalledWith(vehicles);
    } finally {
      fixture.destroy();
      TestBed.resetTestingModule();
      vi.useRealTimers();
    }
  });

  it('replaces neighborhood labels while retaining the place filter and street names', async () => {
    const placeFilter: FilterSpecification = ['!=', ['get', 'class'], 'country'];
    map.getStyle.mockReturnValueOnce({
      layers: [
        { id: 'roads', type: 'symbol', source: 'basemap', 'source-layer': 'transportation_name' },
        { id: 'places', type: 'symbol', source: 'basemap', 'source-layer': 'place', filter: placeFilter },
      ],
    });
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/stops').flush(stops);
    http.expectOne('/api/vehicles').flush(noVehicles);
    await fixture.whenStable();

    expect(map.setFilter).toHaveBeenCalledWith('places', [
      'all',
      placeFilter,
      ['!', ['in', ['get', 'class'], ['literal', ['suburb', 'quarter', 'neighbourhood']]]],
    ]);
    expect(map.setFilter).not.toHaveBeenCalledWith('roads', expect.anything());
    const layerIds = map.addLayer.mock.calls.map(([layer]) => layer.id);
    expect(map.addLayer).toHaveBeenCalledWith(expect.objectContaining({ id: 'district-boundaries' }), 'roads');
    expect(layerIds.indexOf('district-names')).toBeGreaterThan(layerIds.indexOf('line-names'));
  });

  it.each([
    [404, 'There is no timetable for today.'],
    [503, 'Lines could not be loaded. Retry lines'],
  ])('says why the map has no lines and keeps mode controls usable after HTTP %s', async (status, message) => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush({}, { status, statusText: 'Unavailable' });
    http.expectOne('/api/stops').flush(stops);
    http.expectOne('/api/vehicles').flush(noVehicles);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(element.querySelectorAll('button'));
    expect(element.querySelector('h2')?.textContent).toBe('Filters');
    expect(element.querySelector('section')?.textContent.replace(/\s+/g, ' ').trim()).toBe(
      `Filters All U-Bahn Tram Bus ${message}`,
    );
    buttons.find((button) => button.textContent.trim() === 'Tram')?.click();
    await fixture.whenStable();
    expect(setData).toHaveBeenLastCalledWith({ type: 'FeatureCollection', features: [] });
    expect(map.setFilter).toHaveBeenCalledWith('lines-tram', [
      'all',
      ['==', ['get', 'type'], 'tram'],
      ['==', ['get', 'type'], 'tram'],
    ]);
  });
  it.each([
    [404, 'Filters All U-Bahn Tram Bus There is no timetable for today.'],
    [503, 'Filters All U-Bahn Tram Bus'],
  ])('keeps the lines on the map while the next day loads and after HTTP %s', async (status, panel) => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date('2026-10-19T21:59:30Z'));
    const fixture = TestBed.createComponent(MapView);
    try {
      TestBed.tick();
      loadMap();
      http.expectOne('/api/lines?date=2026-10-19').flush(routes);
      http.expectOne('/api/stops').flush(stops);
      http.expectOne('/api/vehicles').flush(noVehicles);
      await fixture.whenStable();

      vi.advanceTimersByTime(60_000);
      TestBed.tick();
      http.expectOne('/api/vehicles').flush(noVehicles);
      const nextDay = http.expectOne('/api/lines?date=2026-10-20');
      expect(setData).toHaveBeenLastCalledWith(routes);
      nextDay.flush({}, { status, statusText: 'Unavailable' });
      await fixture.whenStable();
      expect(setData).toHaveBeenLastCalledWith(routes);
      const element = fixture.nativeElement as HTMLElement;
      expect(element.querySelector('section')?.textContent.replace(/\s+/g, ' ').trim()).toBe(panel);
    } finally {
      fixture.destroy();
      TestBed.resetTestingModule();
      vi.useRealTimers();
    }
  });

  it('says so when the base map style fails, but not once it loaded', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const fixture = TestBed.createComponent(MapView);
    try {
      TestBed.tick();
      http.expectOne((request) => request.url === '/api/lines').flush(routes);
      http.expectOne('/api/stops').flush(stops);
      http.expectOne('/api/vehicles').flush(noVehicles);
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;
      const message = 'The map could not be loaded. Reload the page to try again.';

      for (const listener of errorListeners) listener({ error: new Error('Early error') });
      await fixture.whenStable();
      expect(element.textContent).toContain(message);
      loadStyle();
      await fixture.whenStable();
      expect(element.textContent).not.toContain(message);
      for (const listener of errorListeners) listener({ error: new Error('Tile failed') });
      await fixture.whenStable();
      expect(element.textContent).not.toContain(message);

      fixture.destroy();
      const failed = TestBed.createComponent(MapView);
      TestBed.tick();
      const error = new Error('Style failed');
      for (const listener of errorListeners) listener({ error });
      await failed.whenStable();
      expect((failed.nativeElement as HTMLElement).textContent).toContain(message);
      expect(consoleError).toHaveBeenCalledWith(error);
      failed.destroy();
    } finally {
      consoleError.mockRestore();
    }
  });

  it('retries failed stops while keeping keyboard focus in the Filters panel', async () => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/stops').flush({}, { status: 503, statusText: 'Unavailable' });
    http.expectOne('/api/vehicles').flush(noVehicles);
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
    expect(document.activeElement?.textContent).toBe('Filters');
    expect(element.textContent).not.toContain('Stops could not be loaded');
    fixture.destroy();
  });
  it('retries failed lines from the panel while keeping keyboard focus in it', async () => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush({}, { status: 503, statusText: 'Unavailable' });
    http.expectOne('/api/stops').flush(stops);
    http.expectOne('/api/vehicles').flush(noVehicles);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const retry = Array.from(element.querySelectorAll('button')).find(
      (button) => button.textContent.trim() === 'Retry lines',
    );
    const status = element.querySelector('[role="status"]');
    expect(status?.textContent.trim()).toBe('Lines could not be loaded.');
    retry?.focus();
    retry?.click();
    TestBed.tick();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    await fixture.whenStable();
    expect(document.activeElement?.textContent).toBe('Filters');
    // The same live region stays, so screen readers announce its next message.
    expect(element.querySelector('[role="status"]')).toBe(status);
    expect(status?.textContent.trim()).toBe('');
    expect(setData).toHaveBeenLastCalledWith(routes);
    fixture.destroy();
  });

  it('keeps keyboard focus in the Filters panel when retried lines find no timetable', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const fixture = TestBed.createComponent(MapView);
    try {
      TestBed.tick();
      loadMap();
      http.expectOne((request) => request.url === '/api/lines').flush({}, { status: 503, statusText: 'Unavailable' });
      http.expectOne('/api/stops').flush(stops);
      http.expectOne('/api/vehicles').flush(noVehicles);
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;
      Array.from(element.querySelectorAll('button'))
        .find((button) => button.textContent.trim() === 'Retry lines')
        ?.focus();
      vi.advanceTimersByTime(60_000);
      TestBed.tick();
      http.expectOne((request) => request.url === '/api/lines').flush({}, { status: 404, statusText: 'Not Found' });
      http.expectOne('/api/vehicles').flush(noVehicles);
      await fixture.whenStable();
      expect(element.textContent).toContain('There is no timetable for today.');
      expect(document.activeElement?.textContent).toBe('Filters');
    } finally {
      fixture.destroy();
      TestBed.resetTestingModule();
      vi.useRealTimers();
    }
  });

  it.each([
    ['Retry stops', 'Filters'],
    ['Retry lines', 'Filters'],
    ['U-Bahn', 'U-Bahn'],
  ])('preserves keyboard focus during automatic recovery with %s focused', async (focused, expected) => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const fixture = TestBed.createComponent(MapView);
    try {
      TestBed.tick();
      loadMap();
      http.expectOne((request) => request.url === '/api/lines').flush({}, { status: 503, statusText: 'Unavailable' });
      http.expectOne('/api/stops').flush({}, { status: 503, statusText: 'Unavailable' });
      http.expectOne('/api/vehicles').flush(noVehicles);
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;
      const buttons = Array.from(element.querySelectorAll('button'));
      buttons.find((button) => button.textContent.trim() === focused)?.focus();
      vi.advanceTimersByTime(60_000);
      TestBed.tick();
      http.expectOne((request) => request.url === '/api/lines').flush(routes);
      http.expectOne('/api/stops').flush(stops);
      http.expectOne('/api/vehicles').flush(noVehicles);
      await fixture.whenStable();
      expect(document.activeElement?.textContent.trim()).toBe(expected);
      expect(element.textContent).not.toContain('could not be loaded');
      expect(setData).toHaveBeenLastCalledWith(routes);
    } finally {
      fixture.destroy();
      TestBed.resetTestingModule();
      vi.useRealTimers();
    }
  });

  it("opens a clicked stop's departures in the URL, rings it and focuses the map again when they close", async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/', MapView);
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/stops').flush(stops);
    http.expectOne('/api/vehicles').flush(noVehicles);
    await harness.fixture.whenStable();
    // The map draws the stops with their ids among their properties, where clicks and filters read them.
    expect(setStops).toHaveBeenLastCalledWith(
      expect.objectContaining({
        features: expect.arrayContaining([
          expect.objectContaining({ properties: { id: 'tram', name: 'Tram', modes: ['tram'] } }),
        ]) as unknown,
      }),
    );

    // A stop whose dot reaches into the clicked box from beyond 12 px stays closed.
    const router = TestBed.inject(Router);
    map.queryRenderedFeatures.mockReturnValueOnce([
      { geometry: { type: 'Point', coordinates: [16.37, 48.2] }, properties: { id: 'subway' } },
    ]);
    map.project.mockReturnValueOnce({ dist: () => 13 });
    listeners.get('click')?.({ point: { x: 100, y: 100 } });
    await harness.fixture.whenStable();
    expect(router.url).toBe('/');

    // Two stops are within reach of the click, and the nearer one opens.
    map.queryRenderedFeatures.mockReturnValue([
      { geometry: { type: 'Point', coordinates: [16.37, 48.2] }, properties: { id: 'subway' } },
      { geometry: { type: 'Point', coordinates: [16.38, 48.2] }, properties: { id: 'tram' } },
    ]);
    map.project.mockImplementation(([longitude]: [number, number]) => ({
      dist: () => (longitude === 16.38 ? 3 : 9),
    }));
    const navigated = () => firstValueFrom(router.events.pipe(filter((event) => event instanceof NavigationEnd)));
    let navigation = navigated();
    listeners.get('click')?.({ point: { x: 100, y: 100 } });
    await navigation;
    TestBed.tick();
    expect(router.url).toBe('/?stop=tram');
    expect(map.setFilter).toHaveBeenLastCalledWith('selected-stop', ['==', ['get', 'id'], 'tram']);
    const element = harness.fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h2#departures-heading')?.textContent).toBe('Tram');
    expect(document.activeElement?.id).toBe('departures-heading');
    http.expectOne('/api/stops/tram/departures').flush({ stop: { id: 'tram', name: 'Tram' }, departures: [] });
    await harness.fixture.whenStable();

    navigation = navigated();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await navigation;
    await harness.fixture.whenStable();
    expect(router.url).toBe('/');
    expect(element.querySelector('app-departure-panel')).toBeNull();
    expect(map.setFilter).toHaveBeenLastCalledWith('selected-stop', ['==', ['get', 'id'], '']);
    expect(document.activeElement).toBe(canvas);
  });

  it('opens the map on the stop a link names once the map and the stops have loaded', async () => {
    // A phone, where the sheet covers the map's bottom half and the Filters card its top.
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      const top = this.tagName === 'APP-DEPARTURE-PANEL' ? 422 : 0;
      const bottom = this.getAttribute('aria-labelledby') === 'network-heading' ? 140 : 844;
      return DOMRect.fromRect({ x: 0, y: top, width: 390, height: bottom - top });
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/?stop=bus', MapView);
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/vehicles').flush(noVehicles);
    http.expectOne('/api/stops/bus/departures').flush({ stop: { id: 'bus', name: 'Bus' }, departures: [] });
    TestBed.tick();
    expect(map.easeTo).not.toHaveBeenCalled();

    http.expectOne('/api/stops').flush(stops);
    await harness.fixture.whenStable();
    // The stop lands halfway between the Filters card and the sheet.
    expect(map.easeTo).toHaveBeenCalledExactlyOnceWith({
      center: [16.39, 48.2],
      offset: [0, (140 + 422) / 2 - 844 / 2],
      zoom: 15,
      duration: 0,
    });
    expect((harness.fixture.nativeElement as HTMLElement).querySelector('h2#departures-heading')?.textContent).toBe(
      'Bus',
    );
  });

  it('opens no stop when the link names two', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/?stop=get&stop=id', MapView);
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
    http.expectOne('/api/stops').flush(stops);
    http.expectOne('/api/vehicles').flush(noVehicles);
    await harness.fixture.whenStable();
    expect((harness.fixture.nativeElement as HTMLElement).querySelector('app-departure-panel')).toBeNull();
    expect(map.setFilter).toHaveBeenLastCalledWith('selected-stop', ['==', ['get', 'id'], '']);
  });
});
