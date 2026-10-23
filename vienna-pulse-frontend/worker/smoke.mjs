import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { setTimeout } from 'node:timers/promises';

const production = 'https://pulse.vladdancea.com';
// The production build, Previews included, calls the API on this hostname (src/environments).
const api = 'https://pulse-api.vladdancea.com';
// Previews pass their own URL; the default checks production.
const base = process.env.SMOKE_URL ?? production;
const directory = 'dist/vienna-pulse-frontend/browser';
const index = await readFile(`${directory}/index.html`, 'utf8');
const assets = (await readdir(directory)).filter((file) => /\.(js|css)$/.test(file));
const paths = ['/', ...assets.map((file) => `/${file}`), '/assets/maplibre-gl-worker.mjs'];
const scripts = await Promise.all(assets.map((file) => readFile(`${directory}/${file}`, 'utf8')));
assert.ok(
  scripts.some((script) => script.includes(api)),
  `the build calls the API at ${api}`,
);

for (let attempt = 1; ; attempt++) {
  try {
    for (const path of paths) {
      const response = await fetch(`${base}${path}`, { signal: AbortSignal.timeout(15_000) });
      assert.equal(response.status, 200, `${path} status`);
      const contentType = path === '/' ? /text\/html/ : path.endsWith('.css') ? /text\/css/ : /javascript/;
      assert.match(response.headers.get('Content-Type'), contentType);
      // Only production may be indexed by search engines.
      assert.equal(response.headers.get('X-Robots-Tag'), base === production ? null : 'noindex', `${path} indexing`);
      assert.equal(await response.text(), path === '/' ? index : await readFile(`${directory}${path}`, 'utf8'));
    }
    const response = await fetch(`${base}/api/stops`, {
      headers: { 'Sec-Fetch-Mode': 'navigate' },
      signal: AbortSignal.timeout(15_000),
    });
    assert.equal(response.status, 200, 'API status');
    assert.match(response.headers.get('Content-Type'), /application\/geo\+json/);
    const stops = await response.json();
    assert.equal(stops.type, 'FeatureCollection');
    assert.ok(stops.features.length > 0, 'API serves stops');
    // The site reads the API from another origin, so the API must allow the site's.
    const crossOrigin = await fetch(`${api}/api/stops`, {
      headers: { Origin: base },
      signal: AbortSignal.timeout(15_000),
    });
    assert.equal(crossOrigin.status, 200, 'API hostname status');
    assert.equal(crossOrigin.headers.get('Access-Control-Allow-Origin'), base, 'API allows the site');
    assert.ok((await crossOrigin.json()).features.length > 0, 'API hostname serves stops');
    console.log(`Frontend assets match the build; API serves ${stops.features.length} stops at ${base} and ${api}`);
    break;
  } catch (error) {
    if (attempt === 6) throw error;
    console.log(`Deployment is not ready (attempt ${attempt}/6): ${error.message}`);
    await setTimeout(5000);
  }
}
