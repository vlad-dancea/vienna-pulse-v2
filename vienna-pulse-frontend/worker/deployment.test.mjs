import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { after, before, test } from 'node:test';
import { createTestHarness } from 'wrangler';
import config from '../wrangler.json' with { type: 'json' };
import {
  assertHeadersAsBuilt,
  assertRevalidated,
  assertSiteHeaders,
  securityHeaders,
  serveBuiltHeaders,
} from './headers.mjs';

let server;

before(async () => {
  // The production configuration serves the built files with no Worker script.
  server = createTestHarness({ workers: [{ config }] });
  await server.listen();
});

after(async () => {
  await server?.close();
});

test('serves the production shell, styles, lazy JavaScript and map worker with their headers, including direct navigation', async () => {
  const index = await readFile('dist/vienna-pulse-frontend/browser/index.html', 'utf8');
  // The Content-Security-Policy allows no inline code, so it would block either.
  assert.doesNotMatch(index, /<script(?![^>]*\ssrc=)|<style/);
  // The API has its own hostname, so its old path here is one more page for Angular's router.
  for (const path of ['/', '/map', '/api/stops']) {
    const response = await server.fetch(path, { headers: { 'Sec-Fetch-Mode': 'navigate' } });
    assert.equal(response.status, 200);
    assert.match(response.headers.get('Content-Type'), /text\/html/);
    assertSiteHeaders(path, response.headers);
    assert.equal(await response.text(), index);
  }
  const assets = (await readdir('dist/vienna-pulse-frontend/browser')).filter((file) => /\.(js|css)$/.test(file));
  for (const path of [...assets.map((file) => `/${file}`), '/assets/maplibre-gl-worker.mjs']) {
    const response = await server.fetch(path);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('Content-Type'), path.endsWith('.css') ? /text\/css/ : /javascript/);
    assertSiteHeaders(path, response.headers);
    assert.equal(await response.text(), await readFile(`dist/vienna-pulse-frontend/browser${path}`, 'utf8'));
  }
  // A tab from the previous deploy can still request its old chunks, which the SPA fallback answers.
  for (const path of ['/chunk-REMOVED1.js', '/styles-REMOVED1.css']) {
    const response = await server.fetch(path, { headers: { 'Sec-Fetch-Mode': 'cors' } });
    assert.match(response.headers.get('Content-Type'), /text\/html/);
    assertRevalidated(path, response.headers);
  }
});

test('finds the headers that the built _headers sets on each response, which a deployment must send', async () => {
  const directory = 'dist/vienna-pulse-frontend/browser';
  const [hashed] = (await readdir(directory)).filter((file) => /-[A-Z0-9]{8}\.js$/.test(file));
  const built = await serveBuiltHeaders(config, directory);
  try {
    const site = Object.keys(securityHeaders).map((name) => name.toLowerCase());
    const names = async (url) => [...(await built.headers(new URL(url))).keys()].sort();
    assert.deepEqual(await names('https://pulse.vladdancea.com/'), [...site].sort());
    assert.deepEqual(await names(`https://pulse.vladdancea.com/${hashed}`), [...site, 'cache-control'].sort());
    assert.deepEqual(await names('https://pr-1.pulse.vladdancea.com/'), [...site, 'x-robots-tag'].sort());
    // The smoke test compares a deployment this way, so a missing header or long caching must fail it.
    const expected = await built.headers(new URL('https://pulse.vladdancea.com/'));
    const served = (await server.fetch('/')).headers;
    assertHeadersAsBuilt('/', expected, served);
    const withoutPolicy = new Headers(served);
    withoutPolicy.delete('Content-Security-Policy');
    assert.throws(() => assertHeadersAsBuilt('/', expected, withoutPolicy), /content-security-policy/);
    const cached = new Headers(served);
    cached.set('Cache-Control', 'public, max-age=31536000, immutable');
    assert.throws(() => assertHeadersAsBuilt('/', expected, cached), /caching/);
  } finally {
    await built.close();
  }
});

test('serves bundled boundaries and matching labels for all 23 districts', async () => {
  const collections = [];
  for (const path of ['/data/vienna-districts.geojson', '/data/vienna-district-labels.geojson']) {
    const response = await server.fetch(path);
    assert.equal(response.status, 200);
    assertSiteHeaders(path, response.headers);
    const collection = await response.json();
    assert.equal(collection.type, 'FeatureCollection');
    assert.deepEqual(
      collection.features.map((feature) => feature.properties.number).sort((a, b) => a - b),
      Array.from({ length: 23 }, (_, index) => index + 1),
    );
    collections.push(collection);
  }
  assert.deepEqual(
    collections[0].features.map((feature) => feature.properties),
    collections[1].features.map((feature) => feature.properties),
  );
  assert.ok(collections[0].features.every((feature) => feature.geometry.type === 'Polygon'));
  assert.ok(collections[1].features.every((feature) => feature.geometry.type === 'Point'));
});
