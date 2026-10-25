import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { ComponentFixture } from '@angular/core/testing';
import type { Departure, DepartureLine, StopDepartures } from '../timetable/stop-departures';
import { DeparturePanel } from './departure-panel';

const u3: DepartureLine = {
  id: 'u3',
  name: 'U3',
  longName: null,
  type: 'subway',
  color: '#EF7C00',
  textColor: '#FFFFFF',
};
const tram1: DepartureLine = { ...u3, id: 'tram-1', name: '1', type: 'tram', color: '#C00808' };
const bus4a: DepartureLine = { ...u3, id: 'bus-4a', name: '4A', type: 'bus', color: '#0A295D' };
const bus13a: DepartureLine = { ...bus4a, id: 'bus-13a', name: '13A' };

/** The time `minutes` after 18:00 in Vienna on 2026-10-24, as the API writes it. */
function at(minutes: number): string {
  const seconds = 18 * 3600 + Math.round(minutes * 60);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `2026-10-24T${pad(Math.floor(seconds / 3600))}:${pad(Math.floor(seconds / 60) % 60)}:${pad(seconds % 60)}+02:00`;
}

/** A departure planned `minute` minutes after 18:00 in Vienna, and expected `live` minutes after it if given. */
function departure(line: DepartureLine, headsign: string | null, minute: number, live?: number): Departure {
  return {
    plannedTime: at(minute),
    liveTime: live === undefined ? null : at(live),
    line,
    headsign,
    platform: 'at:49:657:0:1',
  };
}

/** Karlsplatz's departures as the API answers them, by default for a stop without live data. */
function answer(departures: Departure[], liveStatus: StopDepartures['liveStatus'] = 'unavailable'): StopDepartures {
  const liveIn = liveStatus === 'pending' ? 15 : null;
  return { stop: { id: 'at:49:657', name: 'Karlsplatz' }, liveStatus, liveIn, departures };
}

const karlsplatz = '/api/stops/at%3A49%3A657/departures';

describe('DeparturePanel', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<DeparturePanel>;
  let element: HTMLElement;

  /** The words in an element, one space apart wherever the template puts its elements. */
  const words = (node: Element | null) => {
    const texts = node ? document.createTreeWalker(node, NodeFilter.SHOW_TEXT) : undefined;
    const parts: string[] = [];
    while (texts?.nextNode()) {
      const part = texts.currentNode.textContent?.trim();
      if (part) parts.push(part);
    }
    return parts.join(' ');
  };
  const text = (selector?: string) => words(selector ? element.querySelector(selector) : element);

  beforeEach(() => {
    // 18:00 in Vienna.
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date('2026-10-24T16:00:00Z'));
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeparturePanel);
    fixture.componentRef.setInput('stop', 'at:49:657');
    element = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => {
    http.verify();
    fixture.destroy();
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('groups the next two departures of each line and headsign like the displays at the stops', async () => {
    fixture.componentRef.setInput('name', 'Karlsplatz');
    TestBed.tick();
    expect(text('[role="status"]')).toBe('Loading departures...');
    expect(document.activeElement?.textContent).toBe('Karlsplatz');

    http
      .expectOne(karlsplatz)
      .flush(
        answer([
          departure(u3, 'Wien Ottakring', 0),
          departure(bus13a, 'Wien Skodagasse', 2),
          departure(u3, 'Wien Simmering', 3),
          departure(u3, 'Wien Ottakring', 4),
          departure(tram1, 'Wien Prater, Hauptallee', 5),
          departure(bus4a, 'Wien Karlsplatz', 7),
          departure(u3, 'Wien Ottakring', 8),
          departure(u3, 'Wiener Neudorf', 12),
        ]),
      );
    await fixture.whenStable();

    expect(text('[role="status"]')).toBe('');
    expect(Array.from(element.querySelectorAll('li'), words)).toEqual([
      'U3 Ottakring now 18:00 4 min 18:04',
      'U3 Simmering 3 min 18:03',
      'U3 Wiener Neudorf 12 min 18:12',
      '1 Prater, Hauptallee 5 min 18:05',
      '4A Karlsplatz 7 min 18:07',
      '13A Skodagasse 2 min 18:02',
    ]);
    const badge = element.querySelector<HTMLElement>('li span');
    expect([badge?.style.backgroundColor, badge?.style.color]).toEqual(['rgb(239, 124, 0)', 'rgb(255, 255, 255)']);
    expect(element.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-24T18:00:00+02:00');
  });

  it('counts from the live times, strikes through planned times a minute or more away and marks late departures', async () => {
    TestBed.tick();
    http
      .expectOne(karlsplatz)
      .flush(
        answer(
          [
            departure(u3, 'Wien Simmering', -5, 2),
            departure(u3, 'Wien Ottakring', 4, 4.5),
            departure(tram1, 'Wien Prater, Hauptallee', 5),
            departure(bus13a, 'Wien Skodagasse', 10, 8),
            departure(u3, 'Wien Ottakring', 9, 11),
            departure(tram1, 'Wien Prater, Hauptallee', 12, 13),
          ],
          'live',
        ),
      );
    await fixture.whenStable();

    expect(Array.from(element.querySelectorAll('li'), words)).toEqual([
      'U3 Ottakring live 4 min 18:04 live 11 min planned 18:09 expected 18:11',
      // Planned before now, but still to leave.
      'U3 Simmering live 2 min planned 17:55 expected 18:02',
      // No live data for the first one.
      '1 Prater, Hauptallee 5 min 18:05 live 13 min planned 18:12 expected 18:13',
      '13A Skodagasse live 8 min planned 18:10 expected 18:08',
    ]);
    // The 30 seconds of the first U3 to Ottakring leave its planned time out.
    expect(Array.from(element.querySelectorAll('s'), words)).toEqual([
      'planned 18:09',
      'planned 17:55',
      'planned 18:12',
      'planned 18:10',
    ]);
    const late = element.querySelectorAll('time.text-red-700');
    expect(Array.from(late, (time) => [time.textContent, time.getAttribute('datetime')])).toEqual([
      ['18:02', '2026-10-24T18:02:00+02:00'],
    ]);
  });

  it('shows loaders and asks again every 15 seconds until the live times arrive, also after a failed refresh', async () => {
    const loaders = () => element.querySelectorAll('li span[aria-hidden="true"]').length;
    TestBed.tick();
    http
      .expectOne(karlsplatz)
      .flush(answer([departure(u3, 'Wien Ottakring', 4), departure(u3, 'Wien Ottakring', 9)], 'pending'));
    await fixture.whenStable();
    expect(text('ul')).toBe('U3 Ottakring 4 min 18:04 9 min 18:09');
    expect(loaders()).toBe(2);
    expect(text('[role="status"]')).toBe('Loading live times...');

    vi.advanceTimersByTime(15_000);
    TestBed.tick();
    http.expectOne(karlsplatz).flush({}, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(loaders()).toBe(2);
    expect(text('[role="status"]')).toBe('Loading live times...');

    vi.advanceTimersByTime(15_000);
    TestBed.tick();
    http
      .expectOne(karlsplatz)
      .flush(answer([departure(u3, 'Wien Ottakring', 4, 4.5), departure(u3, 'Wien Ottakring', 9, 12)], 'live'));
    await fixture.whenStable();
    expect(text('ul')).toBe('U3 Ottakring live 4 min 18:04 live 11 min planned 18:09 expected 18:12');
    expect(loaders()).toBe(0);
    expect(text('[role="status"]')).toBe('');

    // With the live times there, the next refresh is 30 seconds later.
    vi.advanceTimersByTime(15_000);
    TestBed.tick();
    http.expectNone(karlsplatz);
    vi.advanceTimersByTime(15_000);
    TestBed.tick();
    http.expectOne(karlsplatz);
  });

  it('counts down, drops departures that left and refreshes every 30 seconds, keeping them when a refresh fails', async () => {
    TestBed.tick();
    http.expectOne(karlsplatz).flush(answer([departure(u3, 'Wien Ottakring', 0), departure(u3, 'Wien Ottakring', 1)]));
    await fixture.whenStable();
    expect(text('h2')).toBe('Karlsplatz');

    vi.advanceTimersByTime(20_000);
    await fixture.whenStable();
    expect(text('ul')).toBe('U3 Ottakring now 18:01');

    vi.advanceTimersByTime(10_000);
    TestBed.tick();
    http.expectOne(karlsplatz).flush({}, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(text('ul')).toBe('U3 Ottakring now 18:01');
    expect(text('[role="status"]')).toBe('');

    vi.advanceTimersByTime(30_000);
    TestBed.tick();
    http.expectOne(karlsplatz).flush(answer([]));
    await fixture.whenStable();
    expect(text('ul')).toBe('');
    expect(text('[role="status"]')).toBe('No departures in the next hour.');

    fixture.destroy();
    TestBed.resetTestingModule();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('retries a failed load from its button and keeps keyboard focus in the panel', async () => {
    TestBed.tick();
    http.expectOne(karlsplatz).flush({}, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    const status = element.querySelector('[role="status"]');
    expect(text()).toBe('Departures Close Departures could not be loaded. Retry departures');

    const retry = element.querySelector<HTMLButtonElement>('#retry-departures');
    retry?.focus();
    retry?.click();
    TestBed.tick();
    http.expectOne(karlsplatz).flush(answer([departure(tram1, 'Wien Prater, Hauptallee', 5)]));
    await fixture.whenStable();
    expect(document.activeElement?.textContent).toBe('Karlsplatz');
    // The same live region stays, so screen readers announce its next message.
    expect(element.querySelector('[role="status"]')).toBe(status);
    expect(text()).toBe('Karlsplatz Close 1 Prater, Hauptallee 5 min 18:05');
  });

  it('says when the stop is not in the timetable, without a retry that cannot help', async () => {
    TestBed.tick();
    http.expectOne(karlsplatz).flush({}, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    expect(text()).toBe('Departures Close This stop is not in the timetable.');
  });

  it('shows only the departures of the mode the map shows, and says when that mode has none', async () => {
    fixture.componentRef.setInput('mode', 'subway');
    TestBed.tick();
    http
      .expectOne(karlsplatz)
      .flush(answer([departure(u3, 'Wien Ottakring', 2), departure(tram1, 'Wien Prater, Hauptallee', 5)]));
    await fixture.whenStable();
    expect(text('ul')).toBe('U3 Ottakring 2 min 18:02');

    fixture.componentRef.setInput('mode', 'bus');
    await fixture.whenStable();
    expect(text('ul')).toBe('');
    expect(text('[role="status"]')).toBe('No bus departures in the next hour.');

    fixture.componentRef.setInput('mode', 'all');
    await fixture.whenStable();
    expect(text('ul')).toBe('U3 Ottakring 2 min 18:02 1 Prater, Hauptallee 5 min 18:05');
  });

  it("loads another stop's departures without showing the last stop's", async () => {
    TestBed.tick();
    http.expectOne(karlsplatz).flush(answer([departure(u3, 'Wien Ottakring', 0)]));
    await fixture.whenStable();

    fixture.componentRef.setInput('stop', 'at:49:1468');
    fixture.componentRef.setInput('name', 'Westbahnhof');
    TestBed.tick();
    expect(text()).toBe('Westbahnhof Close Loading departures...');
    expect(document.activeElement?.textContent).toBe('Westbahnhof');
    http.expectOne('/api/stops/at%3A49%3A1468/departures');
  });

  it('asks to close from its button and on Escape', () => {
    const closed = vi.fn();
    fixture.componentRef.instance.closed.subscribe(closed);
    TestBed.tick();
    http.expectOne(karlsplatz);

    element.querySelector<HTMLButtonElement>('button')?.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(closed).toHaveBeenCalledTimes(2);
  });
});
