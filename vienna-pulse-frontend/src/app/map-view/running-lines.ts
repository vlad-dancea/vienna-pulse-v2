import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { computed, DestroyRef, DOCUMENT, inject, Service, signal } from '@angular/core';
import type { MultiLineString } from 'geojson';
import type { ApiFeatureCollection } from '../api-response';
import type { components } from '../openapi';

/** Describes a scheduled line using the public names, mode and colors from the API. */
export type LineProperties = components['schemas']['LineProperties'];

/** Loads today's scheduled routes and follows the service date in Vienna. */
@Service()
export class RunningLines {
  private readonly dateFormat = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Vienna',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  private readonly day = signal(this.dateFormat.format(new Date()));

  readonly routes = httpResource<
    ApiFeatureCollection<components['schemas']['FeatureCollectionLineProperties'], MultiLineString>
  >(() => ({
    url: '/api/lines',
    params: { date: this.day() },
  }));
  readonly unavailable = computed(() => {
    const error = this.routes.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });

  constructor() {
    const document = inject(DOCUMENT);
    const refreshDay = () => {
      this.day.set(this.dateFormat.format(new Date()));
    };
    // Timers can pause in background tabs, so also check as soon as the user returns.
    const timer = setInterval(refreshDay, 60_000);
    document.addEventListener('visibilitychange', refreshDay);
    document.defaultView?.addEventListener('focus', refreshDay);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', refreshDay);
      document.defaultView?.removeEventListener('focus', refreshDay);
    });
  }
}
