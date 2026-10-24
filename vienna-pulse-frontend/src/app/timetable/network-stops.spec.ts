import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NetworkStops } from './network-stops';

describe('NetworkStops', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('retries a failed load until the stops arrive, then cleans up', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const stops = TestBed.inject(NetworkStops);
    const http = TestBed.inject(HttpTestingController);
    TestBed.tick();
    http.expectOne('/api/stops').flush({}, { status: 503, statusText: 'Unavailable' });
    await TestBed.inject(ApplicationRef).whenStable();
    vi.advanceTimersByTime(60_000);
    TestBed.tick();
    const loaded = {
      type: 'FeatureCollection',
      features: [{ type: 'Feature', properties: { name: 'Karlsplatz', modes: ['subway', 'tram'] } }],
    };
    http.expectOne('/api/stops').flush(loaded);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(stops.latest()).toEqual(loaded);
    vi.advanceTimersByTime(60_000);
    TestBed.tick();
    http.expectNone('/api/stops');
    http.verify();
    TestBed.resetTestingModule();
    expect(vi.getTimerCount()).toBe(0);
  });
});
