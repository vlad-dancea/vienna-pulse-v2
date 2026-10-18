import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { setTimeout } from 'node:timers/promises';

const base = 'https://pulse.vladdancea.com';
const directory = 'dist/vienna-pulse-frontend/browser';
const index = await readFile(`${directory}/index.html`, 'utf8');
const assets = (await readdir(directory)).filter((file) => /\.(js|css)$/.test(file));
const paths = ['/', ...assets.map((file) => `/${file}`), '/assets/maplibre-gl-worker.mjs'];

for (let attempt = 1; ; attempt++) {
  try {
    for (const path of paths) {
      const response = await fetch(`${base}${path}`, { signal: AbortSignal.timeout(15_000) });
      assert.equal(response.status, 200, `${path} status`);
      const contentType = path === '/' ? /text\/html/ : path.endsWith('.css') ? /text\/css/ : /javascript/;
      assert.match(response.headers.get('Content-Type'), contentType);
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
    console.log(`Frontend assets match the build; API serves ${stops.features.length} stops at ${base}`);
    break;
  } catch (error) {
    if (attempt === 6) throw error;
    console.log(`Deployment is not ready (attempt ${attempt}/6): ${error.message}`);
    await setTimeout(5000);
  }
}
