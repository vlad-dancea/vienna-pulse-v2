import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { RunningVehicles } from './running-vehicles';

const tram = {
  type: 'FeatureCollection',
  time: '2026-10-20T06:00:00Z',
  features: [
    {
      type: 'Feature',
      id: 'trip-1',
      geometry: { type: 'Point', coordinates: [16.37, 48.2] },
      properties: {
        line: 'tram-62',
        name: '62',
        longName: null,
        type: 'tram',
        color: null,
        textColor: null,
        headsign: 'Oper',
        path: [
          [16.37, 48.2, 0],
          [16.38, 48.2, 30],
        ],
      },
    },
  ],
};

describe('RunningVehicles refreshes', () => {
  let http: HttpTestingController;
  let hidden: boolean;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    hidden = false;
    vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('every 15 seconds while visible, keeping the last vehicles when one fails', async () => {
    const running = TestBed.inject(RunningVehicles);
    TestBed.tick();
    http.expectOne('/api/vehicles').flush(tram);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(running.latest()).toEqual(tram);

    vi.advanceTimersByTime(15_000);
    TestBed.tick();
    http.expectOne('/api/vehicles').flush({}, { status: 503, statusText: 'Unavailable' });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(running.latest()).toEqual(tram);

    vi.advanceTimersByTime(15_000);
    TestBed.tick();
    const none = { type: 'FeatureCollection', time: '2026-10-20T06:00:30Z', features: [] };
    http.expectOne('/api/vehicles').flush(none);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(running.latest()).toEqual(none);

    TestBed.resetTestingModule();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('not while the page is hidden, and at once when it shows again', async () => {
    TestBed.inject(RunningVehicles);
    TestBed.tick();
    http.expectOne('/api/vehicles').flush(tram);
    await TestBed.inject(ApplicationRef).whenStable();

    hidden = true;
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(60_000);
    TestBed.tick();
    http.expectNone('/api/vehicles');

    hidden = false;
    document.dispatchEvent(new Event('visibilitychange'));
    TestBed.tick();
    http.expectOne('/api/vehicles').flush(tram);
  });
});
