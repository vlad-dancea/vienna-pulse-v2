import type { ExpressionSpecification } from 'maplibre-gl';
import type { LineProperties } from '../timetable/running-lines';
import type { StopProperties } from '../timetable/network-stops';
import type { VehicleProperties } from '../timetable/running-vehicles';

// MapLibre reads properties by name, so the names are checked against the API's types here: a field
// the backend renames fails type-checking instead of leaving the map without colors or filters.

/** A property that lines and their vehicles both have, since they share their filters and colors. */
type SharedLineProperty = keyof LineProperties & keyof VehicleProperties;

/** Reads a property of a line or of its vehicles. */
export const lineProperty = (name: SharedLineProperty): ExpressionSpecification => ['get', name];

/** Reads a property of a stop. */
export const stopProperty = (name: keyof StopProperties): ExpressionSpecification => ['get', name];
