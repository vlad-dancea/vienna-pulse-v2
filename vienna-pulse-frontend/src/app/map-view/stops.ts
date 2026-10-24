import type { CircleLayerSpecification } from 'maplibre-gl';

/** Draws the stops from the `stops` source as small dark dots. */
export const stopsLayer: CircleLayerSpecification = {
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
};
