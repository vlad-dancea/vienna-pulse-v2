import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setTimeout } from 'node:timers/promises';
import config from '../wrangler.json' with { type: 'json' };
import { assetPaths, directory } from './build.mjs';
import { assertHeadersAsBuilt, serveBuiltHeaders } from './headers.mjs';

const production = 'https://pulse.vladdancea.com';
// The production build, Previews included, calls the API on this hostname (src/environments).
const api = 'https://pulse-api.vladdancea.com';
// Previews pass their own URL; the default checks production.
const base = process.env.SMOKE_URL ?? production;
const index = await readFile(`${directory}/index.html`, 'utf8');
// A Preview runs this file from main against a pull request's build, which may change the headers,
// so the deployment must send what its own _headers says rather than main's policy.
const local = await serveBuiltHeaders(config);
/** Checks that the deployment sends the headers the build's _headers sets on the URL. */
async function assertBuiltHeaders(url, headers) {
  assertHeadersAsBuilt(url.pathname, await local.headers(url), headers);
}
const assets = await assetPaths();
const paths = ['/', ...assets];
const scripts = await Promise.all(assets.map((path) => readFile(`${directory}${path}`, 'utf8')));
assert.ok(
  scripts.some((script) => script.includes(api)),
  `the build calls the API at ${api}`,
);

try {
  for (let attempt = 1; ; attempt++) {
    try {
      for (const path of paths) {
        const response = await fetch(`${base}${path}`, { signal: AbortSignal.timeout(15_000) });
        assert.equal(response.status, 200, `${path} status`);
        const contentType = path === '/' ? /text\/html/ : path.endsWith('.css') ? /text\/css/ : /javascript/;
        assert.match(response.headers.get('Content-Type'), contentType);
        await assertBuiltHeaders(new URL(path, base), response.headers);
        // Only production may be indexed by search engines.
        assert.equal(response.headers.get('X-Robots-Tag'), base === production ? null : 'noindex', `${path} indexing`);
        assert.equal(await response.text(), path === '/' ? index : await readFile(`${directory}${path}`, 'utf8'));
      }
      // A chunk removed by a deploy gets the SPA fallback, which must not be cached as the chunk.
      // wrangler preview uploads a placeholder script for a Worker without one, and a Preview sends
      // requests for missing files that are not navigations to that script, so only production has
      // the fallback for them.
      if (base === production) {
        const removed = await fetch(`${base}/chunk-REMOVED1.js`, { signal: AbortSignal.timeout(15_000) });
        await assertBuiltHeaders(new URL('/chunk-REMOVED1.js', base), removed.headers);
      }
      // The site reads the API from another origin, so the API must allow the site's.
      const response = await fetch(`${api}/api/stops`, {
        headers: { Origin: base },
        signal: AbortSignal.timeout(15_000),
      });
      assert.equal(response.status, 200, 'API status');
      assert.match(response.headers.get('Content-Type'), /application\/geo\+json/);
      assert.equal(response.headers.get('Access-Control-Allow-Origin'), base, 'API allows the site');
      const stops = await response.json();
      assert.equal(stops.type, 'FeatureCollection');
      assert.ok(stops.features.length > 0, 'API serves stops');
      console.log(`Frontend assets match the build at ${base}; ${api} serves it ${stops.features.length} stops`);
      break;
    } catch (error) {
      if (attempt === 6) throw error;
      console.log(`Deployment is not ready (attempt ${attempt}/6): ${error.message}`);
      await setTimeout(5000);
    }
  }
} finally {
  await local.close();
}
