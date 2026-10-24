import { httpResource } from '@angular/common/http';
import type { HttpResourceRef } from '@angular/common/http';
import { DOCUMENT, inject } from '@angular/core';
import type { components } from './openapi';
import { refreshEvery } from './refresh-every';

/** A stop and the next two departures of each line and headsign there in the next hour, soonest first. */
export type StopDepartures = components['schemas']['Departures'];

/** One trip leaving a stop: when, on which line and towards where. */
export type Departure = components['schemas']['Departure'];

/** The line a departure runs on, with the names, mode and colors the map draws it with. */
export type DepartureLine = components['schemas']['Line'];

/**
 * Loads the departures from the stop `stop` names and reloads them while the page is visible.
 * Call it where `inject` works, because the reloads stop when that injector is destroyed.
 */
export function stopDepartures(stop: () => string): HttpResourceRef<StopDepartures | undefined> {
  const document = inject(DOCUMENT);
  const departures = httpResource<StopDepartures>(() => `/api/stops/${encodeURIComponent(stop())}/departures`);
  // A refresh fills in the departures after the ones that left. Nobody sees a hidden tab, and every
  // request runs a database query, so returning to the tab refreshes at once instead.
  const refresh = () => {
    if (!document.hidden && !departures.isLoading()) {
      departures.reload();
    }
  };
  refreshEvery(30_000, refresh, ['visibilitychange']);
  return departures;
}
