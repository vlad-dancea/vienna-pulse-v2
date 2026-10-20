import type { FilterSpecification, MapLibreMap } from 'maplibre-gl';

/** Replaces neighborhood labels with Vienna's numbered districts and boundaries. */
export function addDistricts(map: MapLibreMap): void {
  const layers = map.getStyle().layers;
  for (const layer of layers) {
    if (layer.type === 'symbol' && layer['source-layer'] === 'place') {
      map.setFilter(layer.id, [
        'all',
        layer.filter ?? true,
        ['!', ['in', ['get', 'class'], ['literal', ['suburb', 'quarter', 'neighbourhood']]]],
      ] as FilterSpecification);
    }
  }

  map.addSource('districts', {
    type: 'geojson',
    data: 'data/vienna-districts.geojson',
    attribution:
      'Datenquelle: <a href="https://data.wien.gv.at">Stadt Wien - data.wien.gv.at</a> (<a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>, simplified)',
  });
  // Boundaries are context, so they stay beneath the base map's street and water names.
  const firstLabel = layers.find((layer) => layer.type === 'symbol')?.id ?? 'lines-bus-casing';
  map.addLayer(
    {
      id: 'district-boundaries',
      type: 'line',
      source: 'districts',
      minzoom: 10,
      paint: {
        'line-color': '#64748b',
        'line-width': ['interpolate', ['linear'], ['zoom'], 10, 1, 14, 1.5],
        'line-dasharray': [4, 3],
        'line-opacity': 0.7,
      },
    },
    firstLabel,
  );
  // Fixed interior points prevent polygon labels repeating across tile boundaries.
  map.addSource('district-labels', { type: 'geojson', data: 'data/vienna-district-labels.geojson' });
  map.addLayer({
    id: 'district-names',
    type: 'symbol',
    source: 'district-labels',
    minzoom: 10,
    // Faded labels still block other labels, so the layer ends when the fade does.
    maxzoom: 15,
    layout: {
      'text-field': [
        'step',
        ['zoom'],
        ['concat', ['to-string', ['get', 'number']], '.'],
        12,
        ['concat', ['to-string', ['get', 'number']], '. ', ['get', 'name']],
      ],
      'text-font': ['Noto Sans Regular'],
      'text-size': ['interpolate', ['linear'], ['zoom'], 10, 12, 14, 16],
      'text-max-width': 10,
      'text-padding': 5,
    },
    paint: {
      'text-color': '#475569',
      'text-halo-color': '#ffffff',
      'text-halo-width': 2,
      'text-opacity': ['interpolate', ['linear'], ['zoom'], 14, 1, 15, 0],
    },
  });
}
