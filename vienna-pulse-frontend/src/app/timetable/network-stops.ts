import { httpResource } from '@angular/common/http';
import { computed, DestroyRef, inject, linkedSignal, Service } from '@angular/core';
import type { FeatureCollection, Point } from 'geojson';
import type { components } from './openapi';

/** Describes a stop complex, with null modes while the backend has not classified the stops yet. */
export type StopProperties = components['schemas']['StopProperties'];

/** Loads stops and rechecks classification while independently deployed services catch up. */
@Service()
export class NetworkStops {
  readonly stops = httpResource<components['schemas']['FeatureCollectionPointStopProperties']>(() => '/api/stops');
  /** The last stops that loaded, kept on the map when a later recheck fails. */
  readonly latest = linkedSignal<
    FeatureCollection<Point, StopProperties> | undefined,
    FeatureCollection<Point, StopProperties> | undefined
  >({
    source: () => (this.stops.hasValue() ? this.stops.value() : undefined),
    computation: (next, previous) => next ?? previous?.value,
  }).asReadonly();
  readonly classified = computed(() => {
    const features = this.latest()?.features ?? [];
    return features.length > 0 && features.every((stop) => Array.isArray(stop.properties.modes));
  });

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
