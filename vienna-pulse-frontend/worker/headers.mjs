import assert from 'node:assert/strict';

/** Headers that public/_headers must send with every page and file of the site. */
export const securityHeaders = {
  'Content-Security-Policy':
    "default-src 'self'; connect-src 'self' https://tiles.openfreemap.org; img-src 'self' data: blob:; " +
    "object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
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
  assert.match(cacheControl, /\b(no-cache|max-age=0)\b/, `${path} caching`);
  assert.doesNotMatch(cacheControl, /immutable/, `${path} caching`);
}
