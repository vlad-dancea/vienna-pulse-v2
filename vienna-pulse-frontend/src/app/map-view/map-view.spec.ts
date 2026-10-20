import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MapView } from './map-view';

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
    for (const layer of ['lines-casing', 'lines', 'line-names']) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, ['==', ['get', 'type'], type]);
    }
    expect(selected?.getAttribute('aria-pressed')).toBe('true');
    expect(map.setFilter).not.toHaveBeenCalledWith('stops', expect.anything());

    buttons.find((button) => button.textContent.trim() === 'All')?.click();
    await fixture.whenStable();
    expect(selected?.getAttribute('aria-pressed')).toBe('false');
    for (const layer of ['lines-casing', 'lines', 'line-names']) {
      expect(map.setFilter).toHaveBeenCalledWith(layer, null);
    }
    http.expectNone((request) => request.url === '/api/lines');
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
    await fixture.whenStable();
    expect(setData).toHaveBeenLastCalledWith(routes);
    expect(element.textContent).toContain('Routes follow the timetable');
  });

  it.each([
    [404, "Today's routes are not available yet."],
    [503, 'Routes could not be loaded.'],
  ])('explains HTTP %s and retries without losing keyboard focus', async (status, message) => {
    const fixture = TestBed.createComponent(MapView);
    TestBed.tick();
    loadMap();
    http.expectOne((request) => request.url === '/api/lines').flush({}, { status, statusText: 'Unavailable' });
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
});
