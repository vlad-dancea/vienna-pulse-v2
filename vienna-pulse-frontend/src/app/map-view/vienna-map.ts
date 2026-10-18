import { InjectionToken } from '@angular/core';

/** Describes the geographic view of Vienna. */
export interface MapCamera {
  center: [number, number];
  zoom: number;
  bearing: number;
  pitch: number;
}

/** Exposes the renderer operations used by the map view. */
export interface ViennaMap {
  jumpTo: (camera: MapCamera) => void;
  remove: () => void;
}

export const VIENNA_CAMERA: MapCamera = {
  center: [16.3738, 48.2082],
  zoom: 12,
  bearing: 0,
  pitch: 0,
};

export const CREATE_VIENNA_MAP = new InjectionToken<typeof createViennaMap>('Create Vienna map', {
  providedIn: 'root',
  factory: () => createViennaMap,
});

export async function createViennaMap(
  container: HTMLElement,
  camera: MapCamera,
  abortSignal: AbortSignal,
  onMove: (camera: MapCamera) => void,
  onLoad: () => void,
  onError: () => void,
): Promise<ViennaMap> {
  const { Map, setWorkerUrl } = await import('maplibre-gl');
  abortSignal.throwIfAborted();
  // Angular serves the module worker locally rather than depending on a CDN.
  setWorkerUrl(new URL('assets/maplibre-gl-worker.mjs', document.baseURI).href);
  const map = new Map({
    container,
    ...camera,
    style: 'https://tiles.openfreemap.org/styles/positron',
    attributionControl: { compact: true },
    minZoom: 9,
    maxZoom: 18,
    fadeDuration: 0,
    reduceMotion: true,
    scrollZoom: false,
    doubleClickZoom: false,
    touchZoomRotate: false,
    dragRotate: false,
    boxZoom: false,
    keyboard: false,
    dragPan: { maxSpeed: 0 },
  });

  map.on('style.load', () => {
    // Keep the basemap geometry while experimenting without labels.
    for (const layer of map.getStyle().layers) {
      if (layer.type === 'symbol') {
        map.setLayoutProperty(layer.id, 'visibility', 'none');
      }
    }
  });

  map.on('move', () => {
    const center = map.getCenter();
    onMove({ center: [center.lng, center.lat], zoom: map.getZoom(), bearing: map.getBearing(), pitch: map.getPitch() });
  });
  map.on('load', onLoad);
  map.on('error', onError);

  map.getCanvas().setAttribute('aria-label', 'Map of Vienna');
  map.getCanvas().addEventListener('keydown', (event) => {
    switch (event.key) {
      case 'ArrowLeft':
        map.panBy([-100, 0], { animate: false });
        break;
      case 'ArrowRight':
        map.panBy([100, 0], { animate: false });
        break;
      case 'ArrowUp':
        map.panBy([0, -100], { animate: false });
        break;
      case 'ArrowDown':
        map.panBy([0, 100], { animate: false });
        break;
      default:
        return;
    }
    event.preventDefault();
  });
  const observer = new ResizeObserver(() => map.resize());
  observer.observe(container);

  return {
    jumpTo: (nextCamera) => {
      const center = map.getCenter();
      // Avoid interrupting a drag when the view already matches the renderer.
      if (
        center.lng === nextCamera.center[0] &&
        center.lat === nextCamera.center[1] &&
        map.getZoom() === nextCamera.zoom &&
        map.getPitch() === nextCamera.pitch &&
        map.getBearing() === nextCamera.bearing
      )
        return;
      map.jumpTo(nextCamera);
    },
    remove: () => {
      observer.disconnect();
      map.remove();
    },
  };
}
