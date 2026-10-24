import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  DOCUMENT,
  effect,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import type { Signal, WritableResource } from '@angular/core';
import type { FeatureCollection } from 'geojson';
import { MapLibreMap, setWorkerUrl } from 'maplibre-gl';
import type { ExpressionSpecification, FilterSpecification, GeoJSONSource } from 'maplibre-gl';
import { NetworkStops } from '../timetable/network-stops';
import { RunningLines } from '../timetable/running-lines';
import { RunningVehicles } from '../timetable/running-vehicles';
import { addDistricts } from './districts';
import { lineLayers, lineNamesLayer, routeLayers } from './lines';
import { lineProperty, stopProperty } from './properties';
import { stopsLayer } from './stops';
import { VehicleAnimation } from './vehicle-animation';
import { vehiclesLayer } from './vehicles';

/** What a map source draws before its features load. */
const noFeatures: FeatureCollection<never, never> = { type: 'FeatureCollection', features: [] };

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
  private readonly map = signal<MapLibreMap | undefined>(undefined);
  private readonly vehicleAnimation = signal<VehicleAnimation | undefined>(undefined);
  protected readonly styleFailed = signal(false);
  protected readonly stopRetryShown = computed(() => !!this.stops.stops.error() && !this.stops.latest());
  protected readonly lineRetryShown = computed(
    () => !!this.lines.routes.error() && !this.lines.latest() && !this.lines.unavailable(),
  );
  /** The loads that can fail with nothing to show instead, in the order of their retry buttons. */
  protected readonly retries = [
    { name: 'stops', shown: this.stopRetryShown, resource: this.stops.stops },
    { name: 'lines', shown: this.lineRetryShown, resource: this.lines.routes },
  ];

  protected reload(resource: WritableResource<unknown>) {
    // The error and retry button disappear after success, so focus the panel's persistent heading.
    this.heading().nativeElement.focus();
    resource.reload();
  }

  constructor() {
    const destroyRef = inject(DestroyRef);
    const document = inject(DOCUMENT);

    effect(() => {
      const hidden = this.retries.filter((retry) => !retry.shown());
      // Polling also removes a retry button, so preserve focus before Angular updates the view.
      if (hidden.some((retry) => document.activeElement?.id === `retry-${retry.name}`)) {
        this.heading().nativeElement.focus();
      }
    });

    this.drawLatest('stops', this.stops.latest);
    this.drawLatest('lines', this.lines.latest);

    effect(() => {
      const animation = this.vehicleAnimation();
      const vehicles = this.vehicles.latest();
      if (animation) {
        // Gliding for the whole time between two refreshes keeps the vehicles moving.
        const reduceMotion = document.defaultView?.matchMedia('(prefers-reduced-motion: reduce)').matches;
        animation.show(vehicles ?? noFeatures, reduceMotion ? 0 : this.vehicles.interval);
      }
    });

    effect(() => {
      const map = this.map();
      const mode = this.selectedMode();
      const filter: ExpressionSpecification | null = mode === 'all' ? null : ['==', lineProperty('type'), mode];
      for (const layer of routeLayers) {
        const layerFilter: FilterSpecification = filter ? ['all', layer.filter, filter] : layer.filter;
        map?.setFilter(`lines-${layer.mode}-casing`, layerFilter);
        map?.setFilter(`lines-${layer.mode}`, layerFilter);
      }
      map?.setFilter('line-names', filter);
      map?.setFilter('vehicles', filter);
      map?.setFilter('stops', mode === 'all' ? null : ['in', mode, stopProperty('modes')]);
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

      // Without its style MapLibre never fires load, so the page would stay empty without a word.
      // A style that loads after an error, and later errors such as a missing tile, leave a usable map.
      const styleErrors = map.on('error', (event) => {
        console.error(event.error);
        this.styleFailed.set(true);
      });
      map.once('style.load', () => {
        styleErrors.unsubscribe();
        this.styleFailed.set(false);
      });

      // Keep routes below stops so platforms stay visible at interchanges.
      map.once('load', () => {
        map.addSource('lines', { type: 'geojson', data: noFeatures });
        for (const layer of lineLayers) {
          map.addLayer(layer);
        }
        map.addSource('stops', { type: 'geojson', data: noFeatures });
        map.addLayer(stopsLayer);
        map.addLayer(lineNamesLayer);
        // Vehicles go above the line names, because they are what changes on the map.
        map.addSource('vehicles', { type: 'geojson', data: noFeatures });
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

  /** Draws the last features that loaded into a map source once the map has loaded. */
  private drawLatest(source: string, features: Signal<FeatureCollection | undefined>) {
    effect(() => {
      const map = this.map();
      const data = features() ?? noFeatures;
      void map?.getSource<GeoJSONSource>(source)?.setData(data);
    });
  }
}
