import { once } from 'node:events';
import { createServer } from 'node:http';
import { expect, test } from '@playwright/test';
import { createTestHarness } from 'wrangler';
import config from '../wrangler.json' with { type: 'json' };

// The map opens at this center and zoom, so a feature's position on screen follows from its coordinates.
const center = [16.3738, 48.2082];
const worldSize = 512 * 2 ** 12;
const background = '#f8fafc';
const stopColor = '#374151';

/** Returns the coordinates drawn `dx` and `dy` CSS pixels from the middle of the 800 x 600 map. */
function offset(dx, dy) {
  const x = ((center[0] + 180) / 360) * worldSize + dx;
  const latitude = (center[1] * Math.PI) / 180;
  const y = ((1 - Math.log(Math.tan(Math.PI / 4 + latitude / 2)) / Math.PI) / 2) * worldSize + dy;
  return [(x / worldSize) * 360 - 180, (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / worldSize))) * 180) / Math.PI];
}

// One stop, line and vehicle per mode, each in the middle of a screen pixel, right of and below
// the filter panel. Stops and vehicles sit in columns, and each line is a horizontal row.
const modes = [
  { type: 'subway', button: 'U-Bahn', line: 'U1', color: '#00ff00', x: 100, lineY: 100 },
  { type: 'tram', button: 'Tram', line: '1', color: '#ff0000', x: 200, lineY: 150 },
  { type: 'bus', button: 'Bus', line: '13A', color: '#0000ff', x: 300, lineY: 200 },
];
const points = Object.fromEntries(
  modes.flatMap((mode) => [
    [`${mode.type}Stop`, [mode.x, 50]],
    [`${mode.type}Line`, [175, mode.lineY]],
    [`${mode.type}Vehicle`, [mode.x, 250]],
  ]),
);
const at = ([dx, dy]) => offset(dx + 0.5, dy + 0.5);

const api = {
  '/api/stops': {
    type: 'FeatureCollection',
    features: modes.map((mode) => ({
      type: 'Feature',
      properties: { name: `${mode.button} stop`, modes: [mode.type] },
      geometry: { type: 'Point', coordinates: at([mode.x, 50]) },
    })),
  },
  '/api/lines': {
    type: 'FeatureCollection',
    features: modes.map((mode) => ({
      type: 'Feature',
      properties: { name: mode.line, longName: null, type: mode.type, color: mode.color, textColor: null },
      geometry: { type: 'MultiLineString', coordinates: [[at([0, mode.lineY]), at([350, mode.lineY])]] },
    })),
  },
  '/api/vehicles': {
    type: 'FeatureCollection',
    features: modes.map((mode, index) => ({
      type: 'Feature',
      id: index + 1,
      properties: {
        line: mode.line,
        name: mode.line,
        longName: null,
        type: mode.type,
        color: mode.color,
        textColor: null,
        headsign: null,
      },
      geometry: { type: 'Point', coordinates: at([mode.x, 250]) },
    })),
  },
};

// The base map comes from OpenFreeMap in production. A plain background keeps the test offline
// and leaves only the app's own layers to look at. Empty glyph ranges draw no text.
const style = {
  version: 8,
  glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
  sources: {},
  layers: [{ id: 'background', type: 'background', paint: { 'background-color': background } }],
};

const backend = createServer((request, response) => {
  const body = api[new URL(request.url, 'http://backend').pathname];
  response.writeHead(body ? 200 : 404, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(body ?? {}));
});
let server;
let origin;

test.beforeAll(async () => {
  backend.listen(0, '127.0.0.1');
  await once(backend, 'listening');
  // The production Worker and assets, with the tunnel origin replaced by the stand-in backend.
  server = createTestHarness({
    workers: [
      {
        config: {
          ...config,
          main: 'dist/worker/index.js',
          no_bundle: true,
          dev: { host: `127.0.0.1:${backend.address().port}`, upstream_protocol: 'http' },
        },
      },
    ],
  });
  origin = (await server.listen()).url.origin;
});

test.afterAll(async () => {
  await server?.close();
  await new Promise((resolve, reject) => backend.close((error) => (error ? reject(error) : resolve())));
});

test.beforeEach(async ({ context }) => {
  // MapLibre clears its canvas after each frame unless asked to keep it, and the test reads it back.
  await context.addInitScript(() => {
    const getContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (type, attributes) {
      return getContext.call(
        this,
        type,
        type.startsWith('webgl') ? { ...attributes, preserveDrawingBuffer: true } : attributes,
      );
    };
  });
  await context.route('**/*', (route) => {
    const url = new URL(route.request().url());
    // District boundaries would cross the points the test reads, and the Worker test checks those files.
    if (url.origin === origin && url.pathname.startsWith('/data/')) {
      return route.fulfill({ json: { type: 'FeatureCollection', features: [] } });
    }
    if (url.origin === origin) return route.continue();
    if (url.pathname === '/styles/positron') return route.fulfill({ json: style });
    if (url.pathname.startsWith('/fonts/')) return route.fulfill({ body: '' });
    return route.abort();
  });
});

/** Reads the map's color at each named point. */
function colors(page) {
  return page.evaluate((points) => {
    const map = document.querySelector('canvas.maplibregl-canvas');
    if (!(map instanceof HTMLCanvasElement)) return {};
    const copy = document.createElement('canvas');
    copy.width = map.width;
    copy.height = map.height;
    const context = copy.getContext('2d');
    context.drawImage(map, 0, 0);
    const hex = (value) => value.toString(16).padStart(2, '0');
    return Object.fromEntries(
      Object.entries(points).map(([name, [dx, dy]]) => {
        const [red, green, blue] = context.getImageData(map.width / 2 + dx, map.height / 2 + dy, 1, 1).data;
        return [name, `#${hex(red)}${hex(green)}${hex(blue)}`];
      }),
    );
  }, points);
}

/** Waits until the map draws the features of the `shown` modes in their colors, and only those. */
async function expectDrawn(page, shown) {
  const expected = Object.fromEntries(
    modes.flatMap((mode) => {
      const visible = shown.includes(mode.type);
      return [
        [`${mode.type}Stop`, visible ? stopColor : background],
        [`${mode.type}Line`, visible ? mode.color : background],
        [`${mode.type}Vehicle`, visible ? mode.color : background],
      ];
    }),
  );
  await expect.poll(() => colors(page), { timeout: 20_000 }).toEqual(expected);
}

test('draws the stops, lines and vehicles from the API and filters them by mode', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });

  await page.goto(origin);
  const all = modes.map((mode) => mode.type);
  await expectDrawn(page, all);

  for (const mode of modes) {
    await page.getByRole('button', { name: mode.button }).click();
    await expectDrawn(page, [mode.type]);
  }

  await page.getByRole('button', { name: 'All' }).click();
  await expectDrawn(page, all);

  expect(errors).toEqual([]);
});
