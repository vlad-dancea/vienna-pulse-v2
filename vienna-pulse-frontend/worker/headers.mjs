import assert from 'node:assert/strict';
import { cp, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { createTestHarness } from 'wrangler';

/** Headers that public/_headers must send with every page and file of the site. */
export const securityHeaders = {
  'Content-Security-Policy':
    "default-src 'self'; connect-src 'self' https://pulse-api.vladdancea.com https://tiles.openfreemap.org; " +
    "img-src 'self' data: blob:; object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Strict-Transport-Security': 'max-age=31536000',
  'Permissions-Policy':
    'camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), hid=(), midi=(), ' +
    'display-capture=(), browsing-topics=()',
};

/** Checks the security headers, and that only hashed JavaScript and CSS may be cached without revalidating. */
export function assertSiteHeaders(path, headers) {
  for (const [name, value] of Object.entries(securityHeaders))
    assert.equal(headers.get(name), value, `${path} ${name}`);
  const hashed = /^\/[^/]+-[A-Z0-9]{8}\.(js|css)$/.test(path);
  if (hashed) assert.equal(headers.get('Cache-Control'), 'public, max-age=31536000, immutable', `${path} caching`);
  else assertRevalidated(path, headers);
}

/** Checks that a response must be revalidated before it is reused. */
export function assertRevalidated(path, headers) {
  // Without Cache-Control a browser may reuse the response for a while on its own estimate.
  const cacheControl = headers.get('Cache-Control') ?? '';
  assert.match(cacheControl, /\b(no-cache|max-age=0)\b/i, `${path} caching`);
  assert.doesNotMatch(cacheControl, /immutable/i, `${path} caching`);
}

/**
 * Serves built files in Cloudflare's local runtime with and without their `_headers`, so what that
 * file changes on a response follows Cloudflare's own reading of it. The files run no code.
 */
export async function serveBuiltHeaders(config, directory) {
  const plain = await mkdtemp(join(tmpdir(), 'vienna-pulse-plain-'));
  await cp(directory, plain, { recursive: true, filter: (source) => basename(source) !== '_headers' });
  // Without routes the runtime answers every hostname from the files.
  const serve = (files) =>
    createTestHarness({
      workers: [{ config: { ...config, routes: [], assets: { ...config.assets, directory: files } } }],
    });
  const servers = [serve(resolve(directory)), serve(plain)];
  await Promise.all(servers.map((server) => server.listen()));
  return {
    /** The headers that `_headers` sets on the URL's response, with null for one it removes. */
    async headers(url) {
      const responses = await Promise.all(servers.map((server) => server.fetch(url.href)));
      // A response whose body is left unread keeps the runtime from closing.
      await Promise.all(responses.map((response) => response.body?.cancel()));
      const [built, bare] = responses.map((response) => response.headers);
      // The local runtime derives the ETag from the folder the files are in, not only their content.
      const names = new Set([...built.keys(), ...bare.keys()].filter((name) => name !== 'etag'));
      return new Map(
        [...names].filter((name) => built.get(name) !== bare.get(name)).map((name) => [name, built.get(name)]),
      );
    },
    close: () => Promise.all(servers.map((server) => server.close())),
  };
}

/**
 * Checks that a deployment sends each of the headers its `_headers` sets on the response, and that
 * a response without a caching rule must be revalidated.
 */
export function assertHeadersAsBuilt(path, expected, actual) {
  assert.ok(expected.size > 0, `${path} gets headers from _headers`);
  for (const [name, value] of expected) assert.equal(actual.get(name), value, `${path} ${name}`);
  if (!expected.has('cache-control')) assertRevalidated(path, actual);
}
