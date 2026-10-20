import { afterNextRender, Component, DestroyRef, effect, ElementRef, inject, signal, viewChild } from '@angular/core';
import { MapLibreMap, setWorkerUrl } from 'maplibre-gl';
import type { FilterSpecification, GeoJSONSource } from 'maplibre-gl';
import { RunningLines } from './running-lines';

/** Displays Vienna's stops and today's scheduled routes with transport-mode controls. */
@Component({
  selector: 'app-map-view',
  templateUrl: './map-view.html',
  host: { class: 'relative block h-dvh' },
})
export class MapView {
  protected readonly lines = inject(RunningLines);
  protected readonly modes = [
    { type: 'all', label: 'All' },
    { type: 'subway', label: 'U-Bahn' },
    { type: 'tram', label: 'Tram' },
    { type: 'bus', label: 'Bus' },
  ];
  protected readonly selectedMode = signal('all');

  private readonly container = viewChild.required<ElementRef<HTMLElement>>('map');
  private readonly status = viewChild.required<ElementRef<HTMLElement>>('status');
  private readonly map = signal<MapLibreMap | undefined>(undefined);

  protected retryRoutes() {
    // The retry button disappears after success, so keep keyboard focus on the status instead.
    this.status().nativeElement.focus();
    this.lines.routes.reload();
  }

  constructor() {
    const destroyRef = inject(DestroyRef);

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
      const map = this.map();
      const mode = this.selectedMode();
      const filter: FilterSpecification | null = mode === 'all' ? null : ['==', ['get', 'type'], mode];
      for (const layer of ['lines-casing', 'lines', 'line-names']) {
        map?.setFilter(layer, filter);
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
      });

      // Keep routes below stops so platforms stay visible at interchanges.
      map.once('load', () => {
        map.addSource('lines', {
          type: 'geojson',
          data: { type: 'FeatureCollection', features: [] },
        });
        map.addLayer({
          id: 'lines-casing',
          type: 'line',
          source: 'lines',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': '#ffffff',
            'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3, 16, 9],
          },
        });
        map.addLayer({
          id: 'lines',
          type: 'line',
          source: 'lines',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': [
              'coalesce',
              ['get', 'color'],
              ['match', ['get', 'type'], 'subway', '#7c3aed', 'tram', '#b91c1c', '#0369a1'],
            ],
            'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1.5, 16, 5],
          },
        });
        map.addSource('stops', { type: 'geojson', data: '/api/stops' });
        map.addLayer({
          id: 'stops',
          type: 'circle',
          source: 'stops',
          paint: {
            'circle-color': '#374151',
            'circle-stroke-color': '#ffffff',
            'circle-stroke-width': 1,
            // Small enough for thousands of stops at city zoom, easy to see on a street.
            'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 2, 16, 6],
          },
        });
        map.addLayer({
          id: 'line-names',
          type: 'symbol',
          source: 'lines',
          minzoom: 12,
          layout: {
            'symbol-placement': 'line',
            'text-font': ['Noto Sans Bold'],
            'text-field': ['get', 'name'],
            'text-size': 12,
            'symbol-spacing': 250,
          },
          paint: {
            'text-color': '#111827',
            'text-halo-color': '#ffffff',
            'text-halo-width': 2,
          },
        });
        this.map.set(map);
      });

      // Releases the map's WebGL context and listeners when the view goes away.
      destroyRef.onDestroy(() => {
        map.remove();
      });
    });
  }
}
