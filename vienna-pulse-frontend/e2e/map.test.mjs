import { expect, test } from '@playwright/test';
import { createTestHarness } from 'wrangler';
import config from '../wrangler.json' with { type: 'json' };
import { securityHeaders } from '../worker/headers.mjs';

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

// The production build reads the API from its own hostname, so the route below stands in for it.
const apiOrigin = 'https://pulse-api.vladdancea.com';
const api = {
  '/api/stops': {
    type: 'FeatureCollection',
    features: modes.map((mode, index) => ({
      type: 'Feature',
      id: `at:49:${index + 1}`,
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
  // Each vehicle waits where it is for its whole path.
  '/api/vehicles': {
    type: 'FeatureCollection',
    time: new Date().toISOString(),
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
        path: [0, 30].map((seconds) => [...at([mode.x, 250]), seconds]),
      },
      geometry: { type: 'Point', coordinates: at([mode.x, 250]) },
    })),
  },
};

// The U-Bahn stop's departures leave a few minutes after the test starts.
const leaving = [4, 9].map((minutes) => new Date(Date.now() + minutes * 60_000));
const u1 = { id: 'u1', name: 'U1', longName: null, type: 'subway', color: '#E3000F', textColor: '#FFFFFF' };
api['/api/stops/at:49:1/departures'] = {
  stop: { id: 'at:49:1', name: 'U-Bahn stop' },
  liveStatus: 'unavailable',
  departures: leaving.map((time) => ({
    plannedTime: time.toISOString(),
    liveTime: null,
    line: u1,
    headsign: 'Wien Leopoldau',
    platform: 'at:49:1:0:1',
  })),
};
const clock = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Vienna', hour: '2-digit', minute: '2-digit' });

// The base map comes from OpenFreeMap in production. A plain background keeps the test offline
// and leaves only the app's own layers to look at. Empty glyph ranges draw no text.
const style = {
  version: 8,
  glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
  sources: {},
  layers: [{ id: 'background', type: 'background', paint: { 'background-color': background } }],
};

let server;
let origin;

test.beforeAll(async () => {
  // The production configuration and its built files.
  server = createTestHarness({ workers: [{ config }] });
  origin = (await server.listen()).url.origin;
});

test.afterAll(async () => {
  await server?.close();
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
    // Chromium skips CORS checks on responses a route fulfills, so the backend's CorsTest and the
    // smoke test cover those headers.
    if (url.origin === apiOrigin) {
      const body = api[decodeURIComponent(url.pathname)];
      return route.fulfill({ status: body ? 200 : 404, json: body ?? {} });
    }
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

  // The Worker serves public/_headers, so the map draws under the production Content-Security-Policy,
  // and the browser reports anything it blocks as a console error.
  const response = await page.goto(origin);
  expect(response?.headers()['content-security-policy']).toBe(securityHeaders['Content-Security-Policy']);
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

test('credits Stadt Wien and Wiener Linien with links to the data and the license, also zoomed out past the districts', async ({
  page,
}) => {
  // A phone's height lets the map zoom out below 10, where the district boundaries no longer draw.
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(origin);

  // Stadt Wien publishes Wiener Linien's timetable, the realtime data the departure panel shows and the
  // district boundaries under CC BY 4.0, which asks for credit with links to the data and the license.
  const credits = page.locator('.maplibregl-ctrl-attrib-inner');
  await expect(credits).toHaveText(
    'MapLibre | Datenquelle: Stadt Wien – data.wien.gv.at (CC BY 4.0, simplified) | ' +
      'Fahrplandaten: Wiener Linien (CC BY 4.0) | Echtzeitdaten: Wiener Linien (CC BY 4.0)',
  );
  const license = 'https://creativecommons.org/licenses/by/4.0/';
  const timetable = 'https://www.data.gv.at/datasets/ab4a73b6-1c2d-42e1-b4d9-049e04889cf0?locale=de';
  const realtime = 'https://www.data.gv.at/datasets/cfba4373-a654-3e0b-80f8-348738169f95?locale=de';
  const links = () =>
    credits
      .getByRole('link')
      .evaluateAll((links) => links.map((link) => [link.textContent, link.getAttribute('href')]));
  expect(await links()).toEqual([
    ['MapLibre', 'https://maplibre.org/'],
    ['Stadt Wien – data.wien.gv.at', 'https://data.wien.gv.at'],
    ['CC BY 4.0', license],
    ['Wiener Linien', timetable],
    ['CC BY 4.0', license],
    ['Wiener Linien', realtime],
    ['CC BY 4.0', license],
  ]);

  // Stadt Wien's terms ask for its credit on Wiener Linien's data too, so it stays once the boundaries hide.
  await page.getByRole('region', { name: 'Map' }).focus();
  await expect(async () => {
    await page.keyboard.press('Minus');
    await expect(credits).toHaveText(
      'MapLibre | Datenquelle: Stadt Wien – data.wien.gv.at | ' +
        'Fahrplandaten: Wiener Linien (CC BY 4.0) | Echtzeitdaten: Wiener Linien (CC BY 4.0)',
      { timeout: 1_000 },
    );
  }).toPass();
  expect(await links()).toEqual([
    ['MapLibre', 'https://maplibre.org/'],
    ['Stadt Wien – data.wien.gv.at', 'https://data.wien.gv.at'],
    ['Wiener Linien', timetable],
    ['CC BY 4.0', license],
    ['Wiener Linien', realtime],
    ['CC BY 4.0', license],
  ]);
});

test("opens a clicked stop's departures, closes them on Escape, opens them from the link and filters them by mode", async ({
  page,
}) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await page.goto(origin);
  await expectDrawn(
    page,
    modes.map((mode) => mode.type),
  );

  // A stop is 4 px wide at this zoom, so the click lands beside it.
  await page.mouse.click(400 + points.subwayStop[0] + 8, 300 + points.subwayStop[1] + 4);
  const panel = page.getByRole('region', { name: 'U-Bahn stop' });
  const row = new RegExp(
    `^\\s*U1\\s*Leopoldau\\s*\\d+ min\\s*${clock.format(leaving[0])}\\s*\\d+ min\\s*${clock.format(leaving[1])}$`,
  );
  await expect(panel.getByRole('listitem')).toHaveCount(1);
  await expect(panel.getByRole('listitem')).toHaveText(row);
  await expect(page).toHaveURL(`${origin}/?stop=at:49:1`);

  await page.keyboard.press('Escape');
  await expect(panel).toBeHidden();
  await expect(page).toHaveURL(`${origin}/`);
  await expect(page.getByRole('region', { name: 'Map' })).toBeFocused();

  await page.goto(`${origin}/?stop=at:49:1`);
  await expect(panel.getByRole('listitem')).toHaveText(row);

  // The board shows only the mode the filter shows.
  await page.getByRole('button', { name: 'Tram' }).click();
  await expect(panel.getByRole('listitem')).toHaveCount(0);
  await expect(panel.getByRole('status')).toHaveText('No tram departures in the next hour.');
  await page.getByRole('button', { name: 'All' }).click();
  await expect(panel.getByRole('listitem')).toHaveText(row);
  expect(errors).toEqual([]);
});

test('opens a stop found by name with the keyboard alone and returns to the search when it closes', async ({
  page,
}) => {
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await page.goto(origin);
  const search = page.getByRole('combobox', { name: 'Find a stop' });
  await expect(search).toBeVisible();

  // The search is the first thing Tab reaches.
  await page.keyboard.press('Tab');
  await expect(search).toBeFocused();
  await page.keyboard.type('stop');
  await expect(page.getByRole('option')).toHaveText([/^\s*Bus stop\s*Bus\s*$/, /Tram stop/, /U-Bahn stop/]);
  await expect(page.getByRole('search').getByRole('status')).toHaveText('3 stops match.');
  await expect(page.getByRole('option', { selected: true })).toHaveText(/Bus stop/);
  await page.keyboard.press('ArrowUp');
  await expect(page.getByRole('option', { selected: true })).toHaveText(/U-Bahn stop/);
  await expect(search).toHaveAttribute('aria-activedescendant', 'stop-search-at:49:1');

  await page.keyboard.press('Enter');
  const panel = page.getByRole('region', { name: 'U-Bahn stop' });
  await expect(panel.getByRole('heading', { name: 'U-Bahn stop' })).toBeFocused();
  await expect(panel.getByRole('listitem')).toHaveCount(1);
  await expect(page).toHaveURL(`${origin}/?stop=at:49:1`);
  await expect(page.getByRole('listbox')).toBeHidden();

  await page.keyboard.press('Escape');
  await expect(panel).toBeHidden();
  await expect(page).toHaveURL(`${origin}/`);
  await expect(search).toBeFocused();
  await expect(search).toHaveValue('stop');
  expect(errors).toEqual([]);
});

/** Tells whether `locator`'s element is what a click at its center reaches, rather than something drawn above it. */
function onTop(locator) {
  return locator.evaluate((element) => {
    const box = element.getBoundingClientRect();
    return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2));
  });
}

test('keeps the list and the focused filter above the departure sheet on a short screen, and the sheet otherwise', async ({
  page,
}) => {
  // A phone held sideways, where the sheet covers the lower half and the list and the filter buttons reach into it.
  await page.setViewportSize({ width: 568, height: 280 });
  await page.goto(`${origin}/?stop=at:49:1`);
  const close = page.getByRole('region', { name: 'U-Bahn stop' }).getByRole('button', { name: 'Close' });
  await expect(page.getByRole('heading', { name: 'U-Bahn stop' })).toBeFocused();
  await page.getByRole('combobox', { name: 'Find a stop' }).focus();
  await page.keyboard.type('stop');
  await page.keyboard.press('ArrowUp');
  const selected = page.getByRole('option', { selected: true });
  await expect(selected).toHaveText(/U-Bahn stop/);
  expect(await onTop(selected)).toBe(true);

  await page.keyboard.press('Escape');
  await expect(page.getByRole('listbox')).toBeHidden();
  expect(await onTop(close)).toBe(true);

  await page.keyboard.press('Tab');
  const all = page.getByRole('button', { name: 'All' });
  await expect(all).toBeFocused();
  expect(await onTop(all)).toBe(true);
});
