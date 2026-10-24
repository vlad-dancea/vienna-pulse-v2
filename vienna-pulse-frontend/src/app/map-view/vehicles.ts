import type { CircleLayerSpecification } from 'maplibre-gl';
import { lineColor } from './lines';

/** Draws the vehicles from the `vehicles` source as dots in their line's color. */
export const vehiclesLayer: CircleLayerSpecification = {
  id: 'vehicles',
  type: 'circle',
  source: 'vehicles',
  paint: {
    'circle-color': lineColor,
    'circle-stroke-color': '#ffffff',
    'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 11, 1, 16, 2],
    // A little larger than the stops, so a vehicle waiting at a stop covers it.
    'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 3, 16, 8],
  },
};
