import { httpResource } from '@angular/common/http';
import { DestroyRef, DOCUMENT, inject, Service } from '@angular/core';
import { latestValue } from './latest-value';
import type { components } from './openapi';

/** Describes a running vehicle by its line, drawn like that line, and where its trip is headed. */
export type VehicleProperties = components['schemas']['VehicleProperties'];

/** Loads where the timetable puts the running vehicles and refreshes it while the page is visible. */
@Service()
export class RunningVehicles {
  /** How often the vehicles are refreshed, and so how long they take to move to the next positions. */
  readonly interval = 15_000;
  readonly vehicles = httpResource<components['schemas']['FeatureCollectionPointVehicleProperties']>(
    () => '/api/vehicles',
  );
  /** The last vehicles that loaded, kept on the map when a refresh fails. */
  readonly latest = latestValue(this.vehicles);

  constructor() {
    const document = inject(DOCUMENT);
    // Nobody sees a hidden tab, and every request runs a database query.
    // Returning refreshes at once instead of showing positions from before the tab was hidden.
    const refresh = () => {
      if (!document.hidden && !this.vehicles.isLoading()) {
        this.vehicles.reload();
      }
    };
    const timer = setInterval(refresh, this.interval);
    document.addEventListener('visibilitychange', refresh);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', refresh);
    });
  }
}
