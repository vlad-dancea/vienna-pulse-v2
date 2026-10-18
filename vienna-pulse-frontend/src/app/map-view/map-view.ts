import { Component, signal } from '@angular/core';
import { VIENNA_CAMERA } from './vienna-map';
import { MapPane } from './map-pane/map-pane';

/** Displays Vienna on a neutral OpenFreeMap basemap. */
@Component({
  selector: 'app-map-view',
  imports: [MapPane],
  templateUrl: './map-view.html',
})
export class MapView {
  protected readonly camera = signal(VIENNA_CAMERA);

  protected zoomBy(amount: number) {
    this.camera.update((camera) => ({ ...camera, zoom: Math.max(9, Math.min(18, camera.zoom + amount)) }));
  }

  protected setPitch(pitch: number) {
    this.camera.update((camera) => ({ ...camera, pitch }));
  }

  protected resetView() {
    this.camera.set(VIENNA_CAMERA);
  }
}
