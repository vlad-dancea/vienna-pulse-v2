import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { FilterSpecification, LayerSpecification } from 'maplibre-gl';
import { MapView } from './map-view';
import { featureFilter } from '@maplibre/maplibre-gl-style-spec';
import { NetworkStops } from './network-stops';

const map = vi.hoisted(() => ({
  once: vi.fn(),
  addSource: vi.fn(),
  addLayer: vi.fn<(layer: LayerSpecification) => void>(),
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
    for (const [layer, filter] of expectedLayers) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, ['all', filter, ['==', ['get', 'type'], type]]);
    }
    expect(map.setFilter).toHaveBeenCalledWith('line-names', ['==', ['get', 'type'], type]);
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

    map.setFilter.mockClear();
    buttons.find((button) => button.textContent.trim() === 'All')?.click();
    await fixture.whenStable();
    expect(selected?.getAttribute('aria-pressed')).toBe('false');
    for (const [layer, filter] of expectedLayers) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, filter);
    }
    expect(map.setFilter).toHaveBeenLastCalledWith('stops', null);
    expect(map.setFilter).toHaveBeenCalledWith('line-names', null);
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
    await fixture.whenStable();
    expect(setData).toHaveBeenLastCalledWith(routes);
    const lineLayers = map.addLayer.mock.calls.map(([layer]) => layer).filter((layer) => layer.type === 'line');
    expect(lineLayers.map((layer) => [layer.id, layer.filter])).toEqual(expectedLayers);
    expect(map.addLayer.mock.calls.map(([layer]) => layer.id)).toEqual([
      ...expectedLayers.map(([id]) => id),
      'stops',
      'line-names',
    ]);
  });

  it.each([404, 503])('keeps the map empty and mode controls usable after HTTP %s', async (status) => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush({}, { status, statusText: 'Unavailable' });
    http.expectOne('/api/stops').flush(stops);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(element.querySelectorAll('button'));
    expect(element.querySelector('h2')?.textContent).toBe('Filters');
    expect(element.querySelector('section')?.textContent.replace(/\s+/g, ' ').trim()).toBe(
      'Filters All U-Bahn Tram Bus',
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

  it('retries failed stops while keeping keyboard focus in the Filters panel', async () => {
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
    expect(document.activeElement?.textContent).toBe('Filters');
    expect(element.textContent).not.toContain('Stops could not be loaded');
    fixture.destroy();
  });
});
