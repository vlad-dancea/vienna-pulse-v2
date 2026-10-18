import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { createViennaMap, CREATE_VIENNA_MAP, VIENNA_CAMERA, type ViennaMap, type MapCamera } from '../vienna-map';
import { MapPane } from './map-pane';

describe('MapPane', () => {
  const createMap = vi.fn<typeof createViennaMap>();

  beforeEach(() => {
    createMap.mockReset();
    TestBed.configureTestingModule({
      imports: [MapPane],
      providers: [{ provide: CREATE_VIENNA_MAP, useValue: createMap }],
    });
  });

  function createPane() {
    const fixture = TestBed.createComponent(MapPane);
    fixture.componentRef.setInput('camera', VIENNA_CAMERA);
    return fixture;
  }

  it('shares user movement without echoing a synchronized camera update', async () => {
    let move: ((camera: MapCamera) => void) | undefined;
    const jumpTo = vi.fn((camera: MapCamera) => {
      move?.(camera);
    });
    createMap.mockImplementation((_container, _camera, _abort, onMove) => {
      move = onMove;
      return Promise.resolve({ jumpTo, remove: vi.fn() });
    });
    const fixture = createPane();
    const emitted: MapCamera[] = [];
    fixture.componentInstance.cameraChange.subscribe((camera) => {
      emitted.push(camera);
    });
    await fixture.whenStable();
    expect(emitted).toEqual([]);

    const nextCamera: MapCamera = { ...VIENNA_CAMERA, center: [16.4, 48.22], zoom: 14, pitch: 45 };
    fixture.componentRef.setInput('camera', nextCamera);
    await fixture.whenStable();
    expect(jumpTo).toHaveBeenLastCalledWith(nextCamera);
    expect(emitted).toEqual([]);

    move?.(nextCamera);
    expect(emitted).toEqual([nextCamera]);
  });

  it('cancels pending initialization and removes a renderer that resolves after destruction', async () => {
    const map = { jumpTo: vi.fn(), remove: vi.fn() };
    let complete: ((map: ViennaMap) => void) | undefined;
    createMap.mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        }),
    );
    const fixture = createPane();
    await fixture.whenStable();
    const abortSignal = createMap.mock.calls[0]?.[2];
    fixture.destroy();
    expect(abortSignal?.aborted).toBe(true);

    complete?.(map);
    await Promise.resolve();
    expect(map.remove).toHaveBeenCalledOnce();
    expect(map.jumpTo).not.toHaveBeenCalled();
  });
});
