import assert from 'node:assert/strict';
import { once } from 'node:events';
import { readFile, readdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { after, before, test } from 'node:test';
import { createTestHarness } from 'wrangler';
import config from '../wrangler.json' with { type: 'json' };

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

before(async () => {
  origin.listen(0, '127.0.0.1');
  await once(origin, 'listening');
  server = createTestHarness({
    workers: [
      {
        config: {
          ...config,
          main: 'dist/worker/index.js',
          no_bundle: true,
          dev: { host: `127.0.0.1:${origin.address().port}`, upstream_protocol: 'http' },
        },
      },
    ],
  });
  await server.listen();
});

after(async () => {
  await server?.close();
  await new Promise((resolve, reject) => origin.close((error) => (error ? reject(error) : resolve())));
});

test('serves the production shell, styles, lazy JavaScript and map worker, including direct navigation', async () => {
  const index = await readFile('dist/vienna-pulse-frontend/browser/index.html', 'utf8');
  for (const path of ['/', '/map']) {
    const response = await server.fetch(path, { headers: { 'Sec-Fetch-Mode': 'navigate' } });
    assert.equal(response.status, 200);
    assert.match(response.headers.get('Content-Type'), /text\/html/);
    assert.equal(await response.text(), index);
  }
  const assets = (await readdir('dist/vienna-pulse-frontend/browser')).filter((file) => /\.(js|css)$/.test(file));
  for (const path of [...assets.map((file) => `/${file}`), '/assets/maplibre-gl-worker.mjs']) {
    const response = await server.fetch(path);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('Content-Type'), path.endsWith('.css') ? /text\/css/ : /javascript/);
    assert.equal(await response.text(), await readFile(`dist/vienna-pulse-frontend/browser${path}`, 'utf8'));
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
