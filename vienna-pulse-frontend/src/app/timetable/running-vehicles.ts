import { httpResource } from '@angular/common/http';
import { DestroyRef, DOCUMENT, inject, linkedSignal, Service } from '@angular/core';
import type { FeatureCollection, Point } from 'geojson';
import type { LineProperties } from './running-lines';

/** Describes a running vehicle by its line, drawn like that line, and where its trip is headed. */
export interface VehicleProperties extends LineProperties {
  line: string;
  headsign: string | null;
}

/** Loads where the timetable puts the running vehicles and refreshes it while the page is visible. */
@Service()
export class RunningVehicles {
  /** How often the vehicles are refreshed, and so how long they take to move to the next positions. */
  readonly interval = 15_000;
  readonly vehicles = httpResource<FeatureCollection<Point, VehicleProperties>>(() => '/api/vehicles');
  /** The last vehicles that loaded, kept on the map when a refresh fails. */
  readonly latest = linkedSignal<
    FeatureCollection<Point, VehicleProperties> | undefined,
    FeatureCollection<Point, VehicleProperties> | undefined
  >({
    source: () => (this.vehicles.hasValue() ? this.vehicles.value() : undefined),
    computation: (next, previous) => next ?? previous?.value,
  }).asReadonly();

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
