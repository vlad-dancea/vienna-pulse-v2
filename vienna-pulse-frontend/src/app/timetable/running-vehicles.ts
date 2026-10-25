import { httpResource } from '@angular/common/http';
import { DOCUMENT, inject, Service } from '@angular/core';
import { latestValue } from './latest-value';
import type { components } from './openapi';
import { refreshEvery } from './refresh-every';

/** Describes a running vehicle by its line, drawn like that line, where its trip is headed and the path it follows next. */
export type VehicleProperties = components['schemas']['VehicleProperties'];

/** The running vehicles, with the time their paths count from. */
export type Vehicles = components['schemas']['Vehicles'];

/** Loads where the timetable puts the running vehicles and refreshes it while the page is visible. */
@Service()
export class RunningVehicles {
  readonly vehicles = httpResource<Vehicles>(() => '/api/vehicles');
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
    // The API sends each vehicle's path for the next 30 seconds, so one refresh can fail or come late
    // before the vehicles stop.
    refreshEvery(15_000, refresh, ['visibilitychange']);
  }
}
