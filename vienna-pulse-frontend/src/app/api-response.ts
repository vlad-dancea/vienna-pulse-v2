import type { Geometry } from 'geojson';

/**
 * A feature collection the API describes, with the one kind of geometry its features have. The
 * API only says that each feature has one of its geometries, and the map reads the kind it knows.
 * The services hand these responses on as GeoJSON types, so a member the backend renames fails
 * to compile there. GeoJSON makes a feature's id optional, but the moving vehicles are told apart
 * by it, so the constraint requires it.
 */
export type ApiFeatureCollection<
  C extends { features: { id: string; geometry: Geometry }[] },
  G extends Geometry,
> = Omit<C, 'features'> & {
  features: (Omit<C['features'][number], 'geometry'> & { geometry: G })[];
};
