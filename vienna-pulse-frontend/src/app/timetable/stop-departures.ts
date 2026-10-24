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
 * Loads the departures from the stop `stop` names and reloads them while the page is visible, every
 * 15 seconds while `pending` says their live times are still to come and every 30 otherwise.
 * Call it where `inject` works, because the reloads stop when that injector is destroyed.
 */
export function stopDepartures(
  stop: () => string,
  pending: () => boolean,
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
  // The backend fetches the live times of a stop it has not fetched recently in its next round, at
  // most 15 seconds later. A change starts the new interval, so it counts from the answer that made it.
  effect((onCleanup) => {
    const timer = setInterval(refresh, pending() ? 15_000 : 30_000);
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
