import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { FilterSpecification, LayerSpecification } from 'maplibre-gl';
import { MapView } from './map-view';

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

  beforeEach(() => {
    vi.clearAllMocks();
    map.once.mockImplementation((_event: string, callback: () => void) => {
      loadMap = callback;
    });
    setData = vi.fn();
    map.getSource.mockReturnValue({ setData });
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
    expect(map.setFilter).not.toHaveBeenCalledWith('stops', expect.anything());

    map.setFilter.mockClear();
    buttons.find((button) => button.textContent.trim() === 'All')?.click();
    await fixture.whenStable();
    expect(selected?.getAttribute('aria-pressed')).toBe('false');
    for (const [layer, filter] of expectedLayers) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, filter);
    }
    expect(map.setFilter).toHaveBeenLastCalledWith('line-names', null);
    http.expectNone((request) => request.url === '/api/lines');
    fixture.destroy();
    expect(map.remove).toHaveBeenCalledOnce();
  });

  it('populates an already loaded map when the routes arrive', async () => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    TestBed.tick();
    http.expectOne((request) => request.url === '/api/lines').flush(routes);
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
});
