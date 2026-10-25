import { httpResource } from '@angular/common/http';
import type { HttpResourceRef } from '@angular/common/http';
import { DestroyRef, DOCUMENT, effect, inject } from '@angular/core';
import type { components } from './openapi';

/**
 * A stop, the next two departures of each line and headsign there, soonest first, and whether their
 * live times have been fetched.
 */
export type StopDepartures = components['schemas']['Departures'];

/** One trip leaving a stop: when the timetable plans it and Wiener Linien expect it, on which line and towards where. */
export type Departure = components['schemas']['Departure'];

/** The line a departure runs on, with the names, mode and colors the map draws it with. */
export type DepartureLine = components['schemas']['Line'];

/**
 * Loads the departures from the stop `stop` names and reloads them while the page is visible. While
 * the answer `shown` returns has its live times still to come, it reloads when that answer's `liveIn`
 * says, at least a second later, or after 15 seconds without one, and every 15 seconds after that.
 * Otherwise it reloads every 30 seconds. Call it where `inject` works, because the reloads stop when
 * that injector is destroyed.
 */
export function stopDepartures(
  stop: () => string,
  shown: () => StopDepartures | undefined,
): HttpResourceRef<StopDepartures | undefined> {
  const document = inject(DOCUMENT);
  const departures = httpResource<StopDepartures>(() => `/api/stops/${encodeURIComponent(stop())}/departures`);
  // A refresh fills in the departures after the ones that left. Nobody sees a hidden tab, and every
  // request runs a database query, so returning to the tab refreshes at once instead.
  const refresh = () => {
    if (!document.hidden && !departures.isLoading()) {
      departures.reload();
    }
  };
  // A pending answer's `liveIn` counts the seconds until the backend may ask Wiener Linien again, and
  // a request that arrives then starts that round or joins it and waits for its answer, so the reload
  // needs no margin. Only while a round outlasts its timeout can `liveIn` fall a little short, and
  // then the reload gets one more pending answer, with a `liveIn` of 1. The reload waits a second at
  // least, so a backend answering 0 is not asked in a loop.
  // Each answer starts the count again. A failed reload keeps the last answer, whose `liveIn` has
  // passed, so the retries come every 15 seconds.
  effect((onCleanup) => {
    const answer = shown();
    const pending = answer?.liveStatus === 'pending';
    const every = pending ? 15_000 : 30_000;
    const first = pending && answer.liveIn !== null ? Math.max(answer.liveIn, 1) * 1000 : every;
    let timer = setInterval(() => {
      refresh();
      clearInterval(timer);
      timer = setInterval(refresh, every);
    }, first);
    onCleanup(() => {
      clearInterval(timer);
    });
  });
  const listeners = new AbortController();
  document.addEventListener('visibilitychange', refresh, { signal: listeners.signal });
  inject(DestroyRef).onDestroy(() => {
    listeners.abort();
  });
  return departures;
}
