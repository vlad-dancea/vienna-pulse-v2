import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NetworkStops } from './network-stops';

describe('NetworkStops upgrade compatibility', () => {
  let http: HttpTestingController;
  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it.each(['empty', 'legacy'])(
    'rechecks an %s backend until classified data arrives, then cleans up',
    async (backend) => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
      const stops = TestBed.inject(NetworkStops);
      http = TestBed.inject(HttpTestingController);
      TestBed.tick();
      const old = {
        type: 'FeatureCollection',
        features: backend === 'empty' ? [] : [{ type: 'Feature', properties: { name: 'Karlsplatz' } }],
      };
      http.expectOne('/api/stops').flush(old);
      await TestBed.inject(ApplicationRef).whenStable();
      expect(stops.classified()).toBe(false);
      vi.advanceTimersByTime(60_000);
      TestBed.tick();
      http.expectOne('/api/stops').flush({
        ...old,
        features: [{ type: 'Feature', properties: { name: 'Karlsplatz', modes: ['subway', 'tram'] } }],
      });
      await TestBed.inject(ApplicationRef).whenStable();
      expect(stops.classified()).toBe(true);
      vi.advanceTimersByTime(60_000);
      TestBed.tick();
      http.expectNone('/api/stops');
      TestBed.resetTestingModule();
      expect(vi.getTimerCount()).toBe(0);
    },
  );
});
