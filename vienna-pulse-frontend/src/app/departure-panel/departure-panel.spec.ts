import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { ComponentFixture } from '@angular/core/testing';
import type { Departure, DepartureLine } from '../timetable/stop-departures';
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

/** A departure at `minute` past 18:00 in Vienna on 2026-10-24. */
function departure(line: DepartureLine, headsign: string | null, minute: number): Departure {
  return {
    time: `2026-10-24T18:${String(minute).padStart(2, '0')}:00+02:00`,
    line,
    headsign,
    platform: 'at:49:657:0:1',
  };
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

    http.expectOne(karlsplatz).flush({
      stop: { id: 'at:49:657', name: 'Karlsplatz' },
      departures: [
        departure(u3, 'Wien Ottakring', 0),
        departure(bus13a, 'Wien Skodagasse', 2),
        departure(u3, 'Wien Simmering', 3),
        departure(u3, 'Wien Ottakring', 4),
        departure(tram1, 'Wien Prater, Hauptallee', 5),
        departure(bus4a, 'Wien Karlsplatz', 7),
        departure(u3, 'Wien Ottakring', 8),
        departure(u3, 'Wiener Neudorf', 12),
      ],
    });
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
    // White on the U3's orange is too faint to read, so its name is black.
    const badge = element.querySelector<HTMLElement>('li span');
    expect([badge?.style.backgroundColor, badge?.style.color]).toEqual(['rgb(239, 124, 0)', 'rgb(0, 0, 0)']);
    expect(element.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-24T18:00:00+02:00');
  });

  it('counts down, drops departures that left and refreshes every 30 seconds, keeping them when a refresh fails', async () => {
    TestBed.tick();
    http.expectOne(karlsplatz).flush({
      stop: { id: 'at:49:657', name: 'Karlsplatz' },
      departures: [departure(u3, 'Wien Ottakring', 0), departure(u3, 'Wien Ottakring', 1)],
    });
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
    http.expectOne(karlsplatz).flush({ stop: { id: 'at:49:657', name: 'Karlsplatz' }, departures: [] });
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
    http.expectOne(karlsplatz).flush({
      stop: { id: 'at:49:657', name: 'Karlsplatz' },
      departures: [departure(tram1, 'Wien Prater, Hauptallee', 5)],
    });
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

  it("loads another stop's departures without showing the last stop's", async () => {
    TestBed.tick();
    http.expectOne(karlsplatz).flush({
      stop: { id: 'at:49:657', name: 'Karlsplatz' },
      departures: [departure(u3, 'Wien Ottakring', 0)],
    });
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
