import { afterNextRender, Component, DestroyRef, ElementRef, inject } from '@angular/core';
import { MapLibreMap, setWorkerUrl } from 'maplibre-gl';

/** Displays a map of Vienna that fills the screen. */
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

      // Releases the map's WebGL context and listeners when the view goes away.
      destroyRef.onDestroy(() => {
        map.remove();
      });
    });
  }
}
