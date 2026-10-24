import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { computed, Service, signal } from '@angular/core';
import { latestValue } from './latest-value';
import type { components } from './openapi';
import { refreshEvery } from './refresh-every';

/** Describes a scheduled line using the public names, mode and colors from the API. */
export type LineProperties = components['schemas']['LineProperties'];

/** Loads today's scheduled routes, follows the service date in Vienna and retries a failed load. */
@Service()
export class RunningLines {
  private readonly dateFormat = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Vienna',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  private readonly day = signal(this.dateFormat.format(new Date()));

  readonly routes = httpResource<components['schemas']['FeatureCollectionMultiLineStringLineProperties']>(() => ({
    url: '/api/lines',
    params: { date: this.day() },
  }));
  /** The last lines that loaded, kept on the map while the next day loads or when a load fails. */
  readonly latest = latestValue(this.routes);
  /** Whether the timetable does not cover today, which a later feed import can fix. */
  readonly unavailable = computed(() => {
    const error = this.routes.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });

  constructor() {
    // A new day loads its own lines, and the same day retries a failed load, such as one that
    // hit a backend restart during a deploy.
    const refresh = () => {
      const day = this.dateFormat.format(new Date());
      if (day !== this.day()) {
        this.day.set(day);
      } else if (this.routes.error() && !this.routes.isLoading()) {
        this.routes.reload();
      }
    };
    // Timers can pause in background tabs, so also check as soon as the user returns.
    refreshEvery(60_000, refresh, ['visibilitychange', 'focus']);
  }
}
