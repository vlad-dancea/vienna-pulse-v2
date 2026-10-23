import assert from 'node:assert/strict';
import { once } from 'node:events';
import { readFile, readdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { after, before, test } from 'node:test';
import { createTestHarness } from 'wrangler';
import config from '../wrangler.json' with { type: 'json' };
import { assertRevalidated, assertSiteHeaders } from './headers.mjs';

const origin = createServer(async (request, response) => {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  response.writeHead(request.url.startsWith('/api/missing') ? 404 : 200, {
    'Content-Type': 'application/json',
    'X-Origin': 'backend',
  });
  response.end(
    JSON.stringify({
      method: request.method,
      path: request.url,
      body: Buffer.concat(chunks).toString(),
      accept: request.headers.accept,
    }),
  );
});
let server;
let preview;

/** Runs the packaged Worker against a local stand-in for the tunnel origin. */
function harness(settings) {
  return createTestHarness({
    workers: [{ config: { ...config, main: 'dist/worker/index.js', no_bundle: true, ...settings } }],
  });
}

before(async () => {
  origin.listen(0, '127.0.0.1');
  await once(origin, 'listening');
  const backend = `127.0.0.1:${origin.address().port}`;
  server = harness({ dev: { host: backend, upstream_protocol: 'http' } });
  // The Preview's own host has nothing listening, so only API_ORIGIN can reach the backend.
  preview = harness({ vars: { API_ORIGIN: `http://${backend}` }, dev: { host: '127.0.0.1:9' } });
  await Promise.all([server.listen(), preview.listen()]);
});

after(async () => {
  await Promise.all([server?.close(), preview?.close()]);
  await new Promise((resolve, reject) => origin.close((error) => (error ? reject(error) : resolve())));
});

test('serves the production shell, styles, lazy JavaScript and map worker with their headers, including direct navigation', async () => {
  const index = await readFile('dist/vienna-pulse-frontend/browser/index.html', 'utf8');
  // The Content-Security-Policy allows no inline code, so it would block either.
  assert.doesNotMatch(index, /<script(?![^>]*\ssrc=)|<style/);
  for (const path of ['/', '/map']) {
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

test('preserves API paths, query strings, methods, bodies and backend errors instead of serving the SPA', async () => {
  for (const path of ['/api', '/api/stops?line=U1', '/api/missing']) {
    const response = await server.fetch(path, {
      headers: { 'Sec-Fetch-Mode': 'navigate', Accept: 'application/geo+json' },
    });
    assert.equal(response.status, path.endsWith('missing') ? 404 : 200);
    assert.equal(response.headers.get('X-Origin'), 'backend');
    assert.deepEqual(await response.json(), { method: 'GET', path, body: '', accept: 'application/geo+json' });
  }
  const response = await server.fetch('/api/stops?line=U2', {
    method: 'POST',
    headers: { Accept: 'application/json' },
    body: 'request body',
  });
  assert.deepEqual(await response.json(), {
    method: 'POST',
    path: '/api/stops?line=U2',
    body: 'request body',
    accept: 'application/json',
  });
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

test('sends Preview API requests to the production origin', async () => {
  const response = await preview.fetch('/api/stops?line=U1', {
    method: 'POST',
    headers: { Accept: 'application/json' },
    body: 'request body',
  });
  assert.equal(response.headers.get('X-Origin'), 'backend');
  assert.deepEqual(await response.json(), {
    method: 'POST',
    path: '/api/stops?line=U1',
    body: 'request body',
    accept: 'application/json',
  });
});
