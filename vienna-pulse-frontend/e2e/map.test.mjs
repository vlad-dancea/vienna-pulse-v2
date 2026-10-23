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
const tramColor = '#ff0000';
const busColor = '#0000ff';

/** Returns the coordinates drawn `dx` and `dy` CSS pixels from the middle of the 800 x 600 map. */
function offset(dx, dy) {
  const x = ((center[0] + 180) / 360) * worldSize + dx;
  const latitude = (center[1] * Math.PI) / 180;
  const y = ((1 - Math.log(Math.tan(Math.PI / 4 + latitude / 2)) / Math.PI) / 2) * worldSize + dy;
  return [(x / worldSize) * 360 - 180, (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / worldSize))) * 180) / Math.PI];
}

// Each feature sits in the middle of a screen pixel, right of and below the filter panel.
const points = {
  tramStop: [100, 50],
  busStop: [200, 50],
  tramLine: [150, 120],
  busLine: [150, 200],
  tramVehicle: [100, 250],
  busVehicle: [200, 250],
};
const at = (name) => offset(points[name][0] + 0.5, points[name][1] + 0.5);

function line(name, type, color, y) {
  return {
    type: 'Feature',
    properties: { name, longName: null, type, color, textColor: null },
    geometry: { type: 'MultiLineString', coordinates: [[offset(0.5, y + 0.5), offset(300.5, y + 0.5)]] },
  };
}

function vehicle(id, line, type, color) {
  return {
    type: 'Feature',
    id,
    properties: { line, name: line, longName: null, type, color, textColor: null, headsign: null },
    geometry: { type: 'Point', coordinates: at(`${type}Vehicle`) },
  };
}

const api = {
  '/api/stops': {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        properties: { name: 'Tram stop', modes: ['tram'] },
        geometry: { type: 'Point', coordinates: at('tramStop') },
      },
      {
        type: 'Feature',
        properties: { name: 'Bus stop', modes: ['bus'] },
        geometry: { type: 'Point', coordinates: at('busStop') },
      },
    ],
  },
  '/api/lines': {
    type: 'FeatureCollection',
    features: [line('1', 'tram', tramColor, points.tramLine[1]), line('13A', 'bus', busColor, points.busLine[1])],
  },
  '/api/vehicles': {
    type: 'FeatureCollection',
    features: [vehicle(1, '1', 'tram', tramColor), vehicle(2, '13A', 'bus', busColor)],
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

/** Waits until the map draws each point in its color, or the background when the point is hidden. */
async function expectDrawn(page, shown) {
  const expected = {
    tramStop: shown.tram ? stopColor : background,
    busStop: shown.bus ? stopColor : background,
    tramLine: shown.tram ? tramColor : background,
    busLine: shown.bus ? busColor : background,
    tramVehicle: shown.tram ? tramColor : background,
    busVehicle: shown.bus ? busColor : background,
  };
  await expect.poll(() => colors(page), { timeout: 20_000 }).toEqual(expected);
}

test('draws the stops, lines and vehicles from the API and filters them by mode', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });

  await page.goto(origin);
  await expectDrawn(page, { tram: true, bus: true });

  await page.getByRole('button', { name: 'Tram' }).click();
  await expectDrawn(page, { tram: true, bus: false });

  await page.getByRole('button', { name: 'Bus' }).click();
  await expectDrawn(page, { tram: false, bus: true });

  await page.getByRole('button', { name: 'All' }).click();
  await expectDrawn(page, { tram: true, bus: true });

  expect(errors).toEqual([]);
});
