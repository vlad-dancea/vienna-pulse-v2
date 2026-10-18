import { afterNextRender, Component, DestroyRef, ElementRef, inject } from '@angular/core';
import { MapLibreMap, setWorkerUrl } from 'maplibre-gl';

/** Displays a full-screen map of Vienna and its public transport stops. */
@Component({
  selector: 'app-map-view',
  template: '',
  host: { class: 'block h-dvh' },
})
export class MapView {
  constructor() {
    const container = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    const destroyRef = inject(DestroyRef);

    // MapLibre sizes the map from its container, which has a size only once it is rendered.
    afterNextRender(() => {
      // MapLibre looks for its worker next to its own script, but Angular bundles that script
      // into a chunk with another name. angular.json copies the worker to assets/ instead.
      setWorkerUrl(new URL('assets/maplibre-gl-worker.mjs', document.baseURI).href);

      const map = new MapLibreMap({
        container,
        // OpenFreeMap serves OpenStreetMap data without an API key.
        style: 'https://tiles.openfreemap.org/styles/positron',
        center: [16.3738, 48.2082],
        zoom: 12,
      });

      // Layers can only be added once the basemap style has loaded. MapLibre fetches the stops
      // itself, which is why the backend also answers its plain JSON request with GeoJSON.
      map.once('load', () => {
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
      });

      // Releases the map's WebGL context and listeners when the view goes away.
      destroyRef.onDestroy(() => {
        map.remove();
      });
    });
  }
}
