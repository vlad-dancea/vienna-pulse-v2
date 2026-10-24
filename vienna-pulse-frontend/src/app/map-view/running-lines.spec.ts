import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { RunningLines } from './running-lines';

describe('RunningLines service date', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it.each([
    ['2026-10-19T21:59:30Z', '2026-10-19', '2026-10-20'],
    ['2026-12-20T22:59:30Z', '2026-12-20', '2026-12-21'],
  ])('refreshes across Vienna midnight from %s', (instant, before, after) => {
    vi.setSystemTime(new Date(instant));
    const lines = TestBed.inject(RunningLines);
    TestBed.tick();
    http.expectOne(`/api/lines?date=${before}`).flush({ type: 'FeatureCollection', features: [] });
    TestBed.tick();

    vi.advanceTimersByTime(60_000);
    TestBed.tick();
    expect(lines.routes.hasValue()).toBe(false);
    http.expectOne(`/api/lines?date=${after}`).flush({ type: 'FeatureCollection', features: [] });
    TestBed.tick();

    vi.advanceTimersByTime(60_000);
    TestBed.tick();
    http.expectNone((request) => request.url === '/api/lines');
    TestBed.resetTestingModule();
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each([503, 404])('retries a failed load with HTTP %s once a minute until it succeeds', async (status) => {
    vi.setSystemTime(new Date('2026-10-19T12:00:00Z'));
    const lines = TestBed.inject(RunningLines);
    const routes = { type: 'FeatureCollection', features: [] };
    TestBed.tick();
    http.expectOne('/api/lines?date=2026-10-19').flush({}, { status, statusText: 'Unavailable' });
    await TestBed.inject(ApplicationRef).whenStable();
    expect(lines.unavailable()).toBe(status === 404);

    vi.advanceTimersByTime(60_000);
    TestBed.tick();
    http.expectOne('/api/lines?date=2026-10-19').flush(routes);
    await TestBed.inject(ApplicationRef).whenStable();
    expect(lines.latest()).toEqual(routes);
    expect(lines.unavailable()).toBe(false);

    vi.advanceTimersByTime(60_000);
    TestBed.tick();
    http.expectNone((request) => request.url === '/api/lines');
  });

  it.each(['visibilitychange', 'focus'])('refreshes on %s after a suspended tab returns', (event) => {
    vi.setSystemTime(new Date('2026-10-19T21:00:00Z'));
    TestBed.inject(RunningLines);
    TestBed.tick();
    http.expectOne('/api/lines?date=2026-10-19').flush({ type: 'FeatureCollection', features: [] });
    TestBed.tick();

    // Advancing the clock without timers models a suspended browser resuming days later.
    vi.setSystemTime(new Date('2026-10-21T06:00:00Z'));
    const target = event === 'focus' ? window : document;
    target.dispatchEvent(new Event(event));
    TestBed.tick();
    http.expectOne('/api/lines?date=2026-10-21').flush({ type: 'FeatureCollection', features: [] });
    TestBed.tick();
  });
});
