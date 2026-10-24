import { readdir } from 'node:fs/promises';
import config from '../wrangler.json' with { type: 'json' };

/** The folder of built files that Wrangler deploys, relative to the frontend. */
export const directory = config.assets.directory;

// Angular names each built JavaScript and CSS file after a hash of its content.
const hashedName = /-[A-Z0-9]{8}\.(js|css)$/;

/** Lists the built files whose names carry a content hash, in name order. */
export async function hashedFiles() {
  return (await readdir(directory)).filter((file) => hashedName.test(file)).sort();
}

/** Tells whether a URL path names a built file with a content hash, which only the top folder holds. */
export function isHashed(path) {
  return /^\/[^/]+$/.test(path) && hashedName.test(path);
}

/** Lists the URL paths of the built JavaScript and CSS, and of the map worker that angular.json copies. */
export async function assetPaths() {
  const files = (await readdir(directory)).filter((file) => /\.(js|css)$/.test(file));
  return [...files.map((file) => `/${file}`), '/assets/maplibre-gl-worker.mjs'];
}
