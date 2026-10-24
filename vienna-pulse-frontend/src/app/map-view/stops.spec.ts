import type { MapLibreMap } from 'maplibre-gl';
import { showStop } from './stops';

describe('showStop', () => {
  // A wide screen: the panel is a column on the right and the Filters card covers the top left corner.
  const box = (left: number, top: number, right: number, bottom: number) =>
    ({
      getBoundingClientRect: () => DOMRect.fromRect({ x: left, y: top, width: right - left, height: bottom - top }),
    }) as HTMLElement;
  const covering = { panel: box(872, 24, 1256, 700), filters: box(24, 24, 300, 146) };

  it.each([
    ['beside the Filters card', [600, 100], false],
    ['in the open', [436, 473], false],
    ['under the Filters card', [150, 100], true],
    ['under the panel', [1000, 300], true],
    ['off the map', [600, 790], true],
  ])('pans a clicked stop %s into view only when it is hidden', (_where, [x, y], pans) => {
    const easeTo = vi.fn();
    const map = {
      getContainer: () => box(0, 0, 1280, 800),
      project: () => ({ x, y }),
      easeTo,
    } as unknown as MapLibreMap;

    showStop(map, [16.37, 48.2], covering, false);

    expect(easeTo.mock.calls).toEqual(pans ? [[{ center: [16.37, 48.2], offset: [436 - 640, 473 - 400] }]] : []);
  });
});
