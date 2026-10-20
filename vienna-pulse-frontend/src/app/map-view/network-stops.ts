import { httpResource } from '@angular/common/http';
import { computed, DestroyRef, inject, Service } from '@angular/core';
import type { FeatureCollection, Point } from 'geojson';

/** Describes a stop complex, with unknown modes while the backend is being upgraded. */
export interface StopProperties {
  name: string;
  modes?: string[] | null;
}

/** Loads stops and rechecks classification while independently deployed services catch up. */
@Service()
export class NetworkStops {
  readonly stops = httpResource<FeatureCollection<Point, StopProperties>>(() => '/api/stops');
  readonly classified = computed(
    () =>
      this.stops.hasValue() &&
      this.stops.value().features.length > 0 &&
      this.stops.value().features.every((stop) => Array.isArray(stop.properties.modes)),
  );

  constructor() {
    const timer = setInterval(() => {
      if (!this.classified() && !this.stops.isLoading()) {
        this.stops.reload();
      }
    }, 60_000);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
    });
  }
}
