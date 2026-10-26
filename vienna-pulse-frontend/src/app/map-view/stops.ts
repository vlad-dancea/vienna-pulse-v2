import type { CircleLayerSpecification, MapLibreMap, Point as ScreenPoint } from 'maplibre-gl';
import type { FeatureCollection, Point } from 'geojson';
import type { StopProperties } from '../timetable/network-stops';
import type { components } from '../timetable/openapi';
import { stopProperty } from './properties';

/** A stop as the map draws it, with its id among its properties, because MapLibre keeps only numeric feature ids. */
export type MapStopProperties = StopProperties & { id: string };

/** Copies each stop's id into its properties, where the map's filters and clicks can read it. */
export function withIds(
  stops: components['schemas']['FeatureCollectionPointStopProperties'] | undefined,
): FeatureCollection<Point, MapStopProperties> | undefined {
  return (
    stops && {
      ...stops,
      features: stops.features.map((stop) => ({ ...stop, properties: { ...stop.properties, id: stop.id } })),
    }
  );
}

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

/** Rings the stop whose departures are open, above the other stops and whichever mode they show. */
export const selectedStopLayer: CircleLayerSpecification = {
  id: 'selected-stop',
  type: 'circle',
  source: 'stops',
  filter: ['==', stopProperty('id'), ''],
  paint: {
    'circle-color': '#ffffff',
    'circle-stroke-color': '#0f172a',
    'circle-stroke-width': 3,
    'circle-radius': ['interpolate', ['linear'], ['zoom'], 11, 5, 16, 8],
  },
};

/** How far from a stop's center a click still opens it, in CSS pixels, since a stop is a few pixels wide at city zoom. */
const reach = 12;

/** The id of the drawn stop nearest to `point` within reach, if any. */
export function stopAt(map: MapLibreMap, point: ScreenPoint): string | undefined {
  const box: [[number, number], [number, number]] = [
    [point.x - reach, point.y - reach],
    [point.x + reach, point.y + reach],
  ];
  let nearest: { id: string; distance: number } | undefined;
  for (const stop of map.queryRenderedFeatures(box, { layers: [stopsLayer.id] })) {
    if (stop.geometry.type === 'Point') {
      // The query also finds stops whose dot reaches into the box from farther away.
      const distance = map.project(stop.geometry.coordinates as [number, number]).dist(point);
      if (distance <= reach && (!nearest || distance < nearest.distance)) {
        nearest = { id: (stop.properties as MapStopProperties).id, distance };
      }
    }
  }
  return nearest?.id;
}

/** Where a rider opened a stop: from a link, by finding its name or by clicking it on the map. */
export type OpenedFrom = 'link' | 'search' | 'map';

/**
 * Moves the map so the stop at `coordinates` is clear of the departure panel and the card with the search
 * and the filters. The map shows a linked or found stop at street level, at once for a link because the
 * page has just loaded, and moves to a clicked stop only when the panel, the card or the edge hides it.
 */
export function showStop(
  map: MapLibreMap,
  coordinates: number[],
  covering: { panel: HTMLElement; card: HTMLElement },
  openedFrom: OpenedFrom,
) {
  const container = map.getContainer().getBoundingClientRect();
  const panel = covering.panel.getBoundingClientRect();
  const card = covering.card.getBoundingClientRect();
  // The panel is a column on the right of wide screens and a sheet at the bottom of phones, so the map
  // stays visible either left of it or above it, whichever is larger, and below the card.
  const left = panel.left - container.left;
  const above = panel.top - container.top;
  const free = {
    top: card.bottom - container.top,
    ...(left * container.height > above * container.width
      ? { right: left, bottom: container.height }
      : { right: container.width, bottom: above }),
  };
  // The API's points are longitude and latitude.
  const center = coordinates as [number, number];
  const offset: [number, number] = [
    free.right / 2 - container.width / 2,
    (free.top + free.bottom) / 2 - container.height / 2,
  ];
  if (openedFrom !== 'map') {
    map.easeTo({ center, offset, zoom: Math.max(map.getZoom(), 15), ...(openedFrom === 'link' && { duration: 0 }) });
    return;
  }
  const point = map.project(center);
  const x = point.x + container.left;
  const y = point.y + container.top;
  const margin = 24;
  const covers = (box: DOMRect) =>
    x > box.left - margin && x < box.right + margin && y > box.top - margin && y < box.bottom + margin;
  const inside =
    x > container.left + margin &&
    x < container.right - margin &&
    y > container.top + margin &&
    y < container.bottom - margin;
  if (!inside || covers(panel) || covers(card)) {
    map.easeTo({ center, offset });
  }
}
