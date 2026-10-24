import { httpResource } from '@angular/common/http';
import { DestroyRef, inject, linkedSignal, Service } from '@angular/core';
import type { FeatureCollection, Point } from 'geojson';
import type { components } from './openapi';

/** Describes a stop complex and the transport modes that let passengers on or off there. */
export type StopProperties = components['schemas']['StopProperties'];

/** Loads the stops and retries a failed load. */
@Service()
export class NetworkStops {
  readonly stops = httpResource<components['schemas']['FeatureCollectionPointStopProperties']>(() => '/api/stops');
  /** The last stops that loaded. */
  readonly latest = linkedSignal<
    FeatureCollection<Point, StopProperties> | undefined,
    FeatureCollection<Point, StopProperties> | undefined
  >({
    source: () => (this.stops.hasValue() ? this.stops.value() : undefined),
    computation: (next, previous) => next ?? previous?.value,
  }).asReadonly();

  constructor() {
    // Only an import changes the stops, so only a failed load, such as one that hit a backend
    // restart during a deploy, loads them again.
    const timer = setInterval(() => {
      if (this.stops.error() && !this.stops.isLoading()) {
        this.stops.reload();
      }
    }, 60_000);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
    });
  }
}
