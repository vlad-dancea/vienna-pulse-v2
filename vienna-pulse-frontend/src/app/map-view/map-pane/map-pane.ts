import {
  afterRenderEffect,
  Component,
  ElementRef,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { CREATE_VIENNA_MAP, type ViennaMap, type MapCamera } from '../vienna-map';

/** Displays the Vienna basemap and reports camera changes. */
@Component({
  selector: 'app-map-pane',
  template: `
    <div class="relative h-dvh bg-zinc-900">
      <div #container class="h-full w-full"></div>
      @if (status() === 'error') {
        <button
          type="button"
          class="absolute bottom-4 left-4 flex size-10 items-center justify-center rounded-lg bg-zinc-950"
          aria-label="Map failed to load. Retry"
          (click)="retry.update(increment)"
        >
          <svg
            aria-hidden="true"
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
          >
            <path d="M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 6M4 12l2 6a7 7 0 0 0 12-1" />
          </svg>
        </button>
      }
    </div>
  `,
  host: { class: 'block' },
})
export class MapPane {
  readonly camera = input.required<MapCamera>();
  readonly cameraChange = output<MapCamera>();
  protected readonly status = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly retry = signal(0);
  private readonly container = viewChild.required<ElementRef<HTMLDivElement>>('container');
  private readonly createMap = inject(CREATE_VIENNA_MAP);
  private map: ViennaMap | undefined;
  private applyingCamera = false;

  constructor() {
    afterRenderEffect((onCleanup) => {
      this.retry();

      let active = true;
      let currentMap: ViennaMap | undefined;
      const controller = new AbortController();
      this.status.set('loading');
      void this.createMap(
        this.container().nativeElement,
        untracked(this.camera),
        controller.signal,
        (camera) => {
          if (active && !this.applyingCamera) this.cameraChange.emit(camera);
        },
        () => {
          if (active) this.status.set('ready');
        },
        () => {
          if (active) {
            this.status.set('error');
          }
        },
      )
        .then((map) => {
          if (!active) {
            map.remove();
            return;
          }
          currentMap = map;
          this.map = map;
          this.applyCamera(untracked(this.camera));
        })
        .catch(() => {
          if (active) {
            this.status.set('error');
          }
        });

      onCleanup(() => {
        active = false;
        controller.abort();
        currentMap?.remove();
        this.map = undefined;
      });
    });

    afterRenderEffect(() => {
      this.applyCamera(this.camera());
    });
  }

  protected readonly increment = (value: number) => value + 1;

  private applyCamera(camera: MapCamera) {
    this.applyingCamera = true;
    try {
      this.map?.jumpTo(camera);
    } finally {
      this.applyingCamera = false;
    }
  }
}
