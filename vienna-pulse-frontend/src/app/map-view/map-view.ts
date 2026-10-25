import {
  afterNextRender,
  afterRenderEffect,
  Component,
  computed,
  DestroyRef,
  DOCUMENT,
  effect,
  ElementRef,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import type { Signal, WritableResource } from '@angular/core';
import type { FeatureCollection } from 'geojson';
import { Router } from '@angular/router';
import { MapLibreMap, setWorkerUrl } from 'maplibre-gl';
import type { ExpressionSpecification, FilterSpecification, GeoJSONSource } from 'maplibre-gl';
import { DeparturePanel } from '../departure-panel/departure-panel';
import { NetworkStops } from '../timetable/network-stops';
import { RunningLines } from '../timetable/running-lines';
import { RunningVehicles } from '../timetable/running-vehicles';
import { addDistricts } from './districts';
import { lineLayers, lineNamesLayer, routeLayers } from './lines';
import { lineProperty, stopProperty } from './properties';
import { selectedStopLayer, showStop, stopAt, stopsLayer, withIds } from './stops';
import { VehicleAnimation } from './vehicle-animation';
import { vehiclesLayer } from './vehicles';

/** What a map source draws before its features load. */
const noFeatures: FeatureCollection<never, never> = { type: 'FeatureCollection', features: [] };

/**
 * Displays Vienna's stops, today's scheduled routes and the running vehicles with transport-mode controls,
 * and the departures of the stop a rider clicks.
 */
@Component({
  imports: [DeparturePanel],
  selector: 'app-map-view',
  templateUrl: './map-view.html',
  host: { class: 'relative block h-dvh' },
})
export class MapView {
  /**
   * The id of the stop whose departures are open, from the `stop` query parameter, so a link opens it.
   * A parameter given twice arrives as a list and opens no stop.
   */
  readonly stop = input(undefined, {
    transform: (value: unknown) => (typeof value === 'string' ? value : undefined),
  });
  private readonly router = inject(Router);
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
  protected readonly selectedStop = computed(() =>
    this.stops.latest()?.features.find((stop) => stop.id === this.stop()),
  );

  private readonly container = viewChild.required<ElementRef<HTMLElement>>('map');
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
  private readonly filters = viewChild.required<ElementRef<HTMLElement>>('filters');
  private readonly panel = viewChild<DeparturePanel, ElementRef<HTMLElement>>(DeparturePanel, { read: ElementRef });
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

  protected closeStop() {
    void this.router.navigate([], { queryParams: {} });
  }

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

    // MapLibre keeps only numeric feature ids, so the stops carry theirs among their properties.
    this.drawLatest(
      'stops',
      computed(() => withIds(this.stops.latest())),
    );
    this.drawLatest('lines', this.lines.latest);

    effect(() => {
      const animation = this.vehicleAnimation();
      const vehicles = this.vehicles.latest();
      if (animation && vehicles) {
        // With reduced motion, the vehicles jump to where they are with each refresh instead.
        const reduceMotion = document.defaultView?.matchMedia('(prefers-reduced-motion: reduce)').matches;
        animation.show(vehicles, !reduceMotion);
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

    effect(() => {
      this.map()?.setFilter('selected-stop', ['==', stopProperty('id'), this.stop() ?? '']);
    });

    effect(() => {
      // Closing removes the panel and the focus in it, so move focus to the map before Angular updates the view.
      const panel = this.panel()?.nativeElement;
      const active = document.activeElement;
      if (!this.stop() && panel && (active === document.body || panel.contains(active))) {
        this.map()?.getCanvas().focus();
      }
    });

    // A link opens the map on its stop once the map and the stops have loaded. A stop opened later
    // moves into view only when the panel or the edge of the map hides it.
    let linked = true;
    afterRenderEffect(() => {
      const map = this.map();
      const stop = this.selectedStop();
      const panel = this.panel()?.nativeElement;
      if (map && this.stops.latest()) {
        untracked(() => {
          if (stop && panel) {
            showStop(map, stop.geometry.coordinates, { panel, filters: this.filters().nativeElement }, linked);
          }
          linked = false;
        });
      }
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
        // Wiener Linien publish the departure panel's live times under CC BY 4.0, which asks for
        // credit. These options replace MapLibre's defaults, so they repeat its compact control and link.
        attributionControl: {
          compact: true,
          customAttribution: [
            '<a href="https://maplibre.org/" target="_blank">MapLibre</a>',
            'Echtzeitdaten: <a href="https://www.data.gv.at/datasets/cfba4373-a654-3e0b-80f8-348738169f95?locale=de">Wiener Linien</a> (<a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>)',
          ],
        },
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
        map.addLayer(selectedStopLayer);
        map.addLayer(lineNamesLayer);
        // Vehicles go above the line names, because they are what changes on the map.
        map.addSource('vehicles', { type: 'geojson', data: noFeatures });
        map.addLayer(vehiclesLayer);
        addDistricts(map);
        map.on('click', (event) => {
          const stop = stopAt(map, event.point);
          if (stop) {
            void this.router.navigate([], { queryParams: { stop } });
          }
        });
        map.on('mousemove', (event) => {
          map.getCanvas().style.cursor = stopAt(map, event.point) ? 'pointer' : '';
        });
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
