import type { ExpressionSpecification, LineLayerSpecification, SymbolLayerSpecification } from 'maplibre-gl';
import { lineProperty } from './properties';

/** The transport modes that get their own route layers, from the bottom up, and the routes each one draws. */
export const routeLayers: { mode: string; filter: ExpressionSpecification }[] = [
  // Keep any other API route types visible with buses, below the tram and U-Bahn layers.
  { mode: 'bus', filter: ['!', ['in', lineProperty('type'), ['literal', ['tram', 'subway']]]] },
  { mode: 'tram', filter: ['==', lineProperty('type'), 'tram'] },
  { mode: 'subway', filter: ['==', lineProperty('type'), 'subway'] },
];

/** The color of a line the feed gives none, by its mode. */
export function modeColor(type: string): string {
  return type === 'subway' ? '#7c3aed' : type === 'tram' ? '#b91c1c' : '#0369a1';
}

/** Gives lines and their vehicles the line's color, or one color per mode when the feed has none. */
export const lineColor: ExpressionSpecification = [
  'coalesce',
  lineProperty('color'),
  ['match', lineProperty('type'), 'subway', modeColor('subway'), 'tram', modeColor('tram'), modeColor('bus')],
];

const lineLayout: LineLayerSpecification['layout'] = {
  'line-cap': 'round',
  'line-join': 'round',
};

/**
 * Draws the routes from the `lines` source as a white casing and a colored stroke per mode.
 * Each mode's casing must also cover lower-priority strokes at crossings.
 */
export const lineLayers: LineLayerSpecification[] = routeLayers.flatMap((layer): LineLayerSpecification[] => [
  {
    id: `lines-${layer.mode}-casing`,
    type: 'line',
    source: 'lines',
    filter: layer.filter,
    layout: lineLayout,
    paint: {
      'line-color': '#ffffff',
      'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3, 16, 9],
    },
  },
  {
    id: `lines-${layer.mode}`,
    type: 'line',
    source: 'lines',
    filter: layer.filter,
    layout: lineLayout,
    paint: {
      'line-color': lineColor,
      'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1.5, 16, 5],
    },
  },
]);

/** Writes each line's name along its route from the `lines` source. */
export const lineNamesLayer: SymbolLayerSpecification = {
  id: 'line-names',
  type: 'symbol',
  source: 'lines',
  minzoom: 12,
  layout: {
    'symbol-placement': 'line',
    'text-font': ['Noto Sans Bold'],
    'text-field': lineProperty('name'),
    'text-size': 12,
    'symbol-spacing': 250,
  },
  paint: {
    'text-color': '#111827',
    'text-halo-color': '#ffffff',
    'text-halo-width': 2,
  },
};
