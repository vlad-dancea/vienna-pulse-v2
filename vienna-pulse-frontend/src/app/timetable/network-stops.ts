import { httpResource } from '@angular/common/http';
import { Service } from '@angular/core';
import { latestValue } from './latest-value';
import type { components } from './openapi';
import { refreshEvery } from './refresh-every';

/** Describes a stop complex and the transport modes that let passengers on or off there. */
export type StopProperties = components['schemas']['StopProperties'];

/** Loads the stops and retries a failed load. */
@Service()
export class NetworkStops {
  readonly stops = httpResource<components['schemas']['FeatureCollectionPointStopProperties']>(() => '/api/stops');
  /** The last stops that loaded. */
  readonly latest = latestValue(this.stops);

  constructor() {
    // Only an import changes the stops, so only a failed load, such as one that hit a backend
    // restart during a deploy, loads them again.
    refreshEvery(60_000, () => {
      if (this.stops.error() && !this.stops.isLoading()) {
        this.stops.reload();
      }
    });
  }
}
