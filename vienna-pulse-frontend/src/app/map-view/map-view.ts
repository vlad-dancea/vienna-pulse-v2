import {
  afterNextRender,
  Component,
  DestroyRef,
  DOCUMENT,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { MapLibreMap, setWorkerUrl } from 'maplibre-gl';
import type { ExpressionSpecification, FilterSpecification, GeoJSONSource } from 'maplibre-gl';
import { NetworkStops } from '../timetable/network-stops';
import { RunningLines } from '../timetable/running-lines';
import { RunningVehicles } from '../timetable/running-vehicles';
import { addDistricts } from './districts';
import { lineLayers, lineNamesLayer, routeLayers } from './lines';
import { stopsLayer } from './stops';
import { VehicleAnimation } from './vehicle-animation';
import { vehiclesLayer } from './vehicles';

/** Displays Vienna's stops, today's scheduled routes and the running vehicles with transport-mode controls. */
@Component({
  selector: 'app-map-view',
  templateUrl: './map-view.html',
  host: { class: 'relative block h-dvh' },
})
export class MapView {
  protected readonly lines = inject(RunningLines);
  protected readonly stops = inject(NetworkStops);
  private readonly vehicles = inject(RunningVehicles);
  protected readonly modes = [
    { type: 'all', label: 'All' },
    { type: 'subway', label: 'U-Bahn' },
    { type: 'tram', label: 'Tram' },
    { type: 'bus', label: 'Bus' },
  ];
  protected readonly selectedMode = signal('all');

  private readonly container = viewChild.required<ElementRef<HTMLElement>>('map');
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
  private readonly stopRetry = viewChild<ElementRef<HTMLButtonElement>>('stopRetry');
  private readonly map = signal<MapLibreMap | undefined>(undefined);
  private readonly vehicleAnimation = signal<VehicleAnimation | undefined>(undefined);

  protected retryStops() {
    // The error and retry button disappear after success, so focus the panel's persistent heading.
    this.heading().nativeElement.focus();
    this.stops.stops.reload();
  }

  constructor() {
    const destroyRef = inject(DestroyRef);
    const document = inject(DOCUMENT);

    effect(() => {
      const error = this.stops.stops.error();
      const retry = this.stopRetry()?.nativeElement;
      // Polling also removes the retry button, so preserve focus before Angular updates the view.
      if (!error && retry && document.activeElement === retry) {
        this.heading().nativeElement.focus();
      }
    });

    effect(() => {
      const map = this.map();
      const stops = this.stops.latest();
      if (map) {
        void map.getSource<GeoJSONSource>('stops')?.setData(stops ?? { type: 'FeatureCollection', features: [] });
      }
    });

    effect(() => {
      const map = this.map();
      const routes = this.lines.routes;
      if (map) {
        void map
          .getSource<GeoJSONSource>('lines')
          ?.setData(routes.hasValue() ? routes.value() : { type: 'FeatureCollection', features: [] });
      }
    });

    effect(() => {
      const animation = this.vehicleAnimation();
      const vehicles = this.vehicles.latest();
      if (animation) {
        // Gliding for the whole time between two refreshes keeps the vehicles moving.
        const reduceMotion = document.defaultView?.matchMedia('(prefers-reduced-motion: reduce)').matches;
        animation.show(
          vehicles ?? { type: 'FeatureCollection', features: [] },
          reduceMotion ? 0 : this.vehicles.interval,
        );
      }
    });

    effect(() => {
      const map = this.map();
      const mode = this.selectedMode();
      const classified = this.stops.classified();
      const filter: ExpressionSpecification | null = mode === 'all' ? null : ['==', ['get', 'type'], mode];
      for (const layer of routeLayers) {
        const layerFilter: FilterSpecification = filter ? ['all', layer.filter, filter] : layer.filter;
        map?.setFilter(`lines-${layer.mode}-casing`, layerFilter);
        map?.setFilter(`lines-${layer.mode}`, layerFilter);
      }
      map?.setFilter('line-names', filter);
      map?.setFilter('vehicles', filter);
      map?.setFilter('stops', mode === 'all' || !classified ? null : ['in', mode, ['get', 'modes']]);
    });

    // MapLibre sizes the map from its container, which has a size only once it is rendered.
    afterNextRender(() => {
      // MapLibre looks for its worker next to its own script, but Angular bundles that script
      // into a chunk with another name. angular.json copies the worker to assets/ instead.
      setWorkerUrl(new URL('assets/maplibre-gl-worker.mjs', document.baseURI).href);

      const map = new MapLibreMap({
        container: this.container().nativeElement,
        // OpenFreeMap serves OpenStreetMap data without an API key.
        style: 'https://tiles.openfreemap.org/styles/positron',
        center: [16.3738, 48.2082],
        zoom: 12,
        // Include the surrounding network through Baden. MapLibre also limits zoom-out
        // to keep the viewport inside these bounds as the screen size changes.
        maxBounds: [
          [16.1, 47.95],
          [16.65, 48.4],
        ],
      });

      // Keep routes below stops so platforms stay visible at interchanges.
      map.once('load', () => {
        map.addSource('lines', {
          type: 'geojson',
          data: { type: 'FeatureCollection', features: [] },
        });
        for (const layer of lineLayers) {
          map.addLayer(layer);
        }
        map.addSource('stops', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
        map.addLayer(stopsLayer);
        map.addLayer(lineNamesLayer);
        // Vehicles go above the line names, because they are what changes on the map.
        map.addSource('vehicles', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
        map.addLayer(vehiclesLayer);
        addDistricts(map);
        const vehicles = map.getSource<GeoJSONSource>('vehicles');
        if (vehicles && document.defaultView) {
          this.vehicleAnimation.set(new VehicleAnimation(vehicles, document.defaultView));
        }
        this.map.set(map);
      });

      // Releases the map's WebGL context and listeners when the view goes away.
      destroyRef.onDestroy(() => {
        this.vehicleAnimation()?.stop();
        map.remove();
      });
    });
  }
}
