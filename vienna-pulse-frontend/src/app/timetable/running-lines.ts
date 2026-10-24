import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { computed, DestroyRef, DOCUMENT, inject, linkedSignal, Service, signal } from '@angular/core';
import type { FeatureCollection, MultiLineString } from 'geojson';

/** Describes a scheduled line using the public names, mode and colors from the API. */
export interface LineProperties {
  name: string;
  longName: string | null;
  type: string;
  color: string | null;
  textColor: string | null;
}

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

  readonly routes = httpResource<FeatureCollection<MultiLineString, LineProperties>>(() => ({
    url: '/api/lines',
    params: { date: this.day() },
  }));
  /** The last lines that loaded, kept on the map while the next day loads or when a load fails. */
  readonly latest = linkedSignal<
    FeatureCollection<MultiLineString, LineProperties> | undefined,
    FeatureCollection<MultiLineString, LineProperties> | undefined
  >({
    source: () => (this.routes.hasValue() ? this.routes.value() : undefined),
    computation: (next, previous) => next ?? previous?.value,
  }).asReadonly();
  /** Whether the timetable does not cover today, which a later feed import can fix. */
  readonly unavailable = computed(() => {
    const error = this.routes.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });

  constructor() {
    const document = inject(DOCUMENT);
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
    const timer = setInterval(refresh, 60_000);
    document.addEventListener('visibilitychange', refresh);
    document.defaultView?.addEventListener('focus', refresh);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', refresh);
      document.defaultView?.removeEventListener('focus', refresh);
    });
  }
}
