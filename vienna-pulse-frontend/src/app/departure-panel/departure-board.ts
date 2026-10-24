import { modeColor } from '../map-view/lines';
import type { Departure, DepartureLine } from '../timetable/stop-departures';

/** A line towards one headsign and its next departures, as one row of the board. */
export interface BoardRow {
  key: string;
  line: string;
  background: string;
  text: string;
  headsign: string;
  departures: { time: string; clock: string; wait: string }[];
}

// The order of the map's mode filter. Other modes follow the buses.
const modes = ['subway', 'tram', 'bus'];

const clock = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Vienna', hour: '2-digit', minute: '2-digit' });

/**
 * Groups the departures that have not left at `now` by line and headsign, with each group's next two,
 * like the displays at the stops. Rows are sorted by mode and line, so a line keeps its place as
 * departures leave and the board refreshes.
 */
export function boardRows(departures: readonly Departure[], now: number): BoardRow[] {
  const rows = new Map<string, { line: DepartureLine; headsign: string | null; departures: Departure[] }>();
  for (const departure of departures) {
    if (Date.parse(departure.plannedTime) < now) {
      continue;
    }
    const key = JSON.stringify([departure.line.id, departure.headsign]);
    const row = rows.get(key) ?? { line: departure.line, headsign: departure.headsign, departures: [] };
    row.departures.push(departure);
    rows.set(key, row);
  }
  return [...rows]
    .sort(
      ([, a], [, b]) =>
        modeRank(a.line.type) - modeRank(b.line.type) ||
        a.line.name.localeCompare(b.line.name, 'de', { numeric: true }) ||
        (a.headsign ?? '').localeCompare(b.headsign ?? '', 'de'),
    )
    .map(([key, row]) => ({
      key,
      line: row.line.name,
      ...badgeColors(row.line),
      // The displays at the stops leave out the "Wien " that most headsigns start with.
      headsign: row.headsign?.replace(/^Wien /, '') ?? '',
      departures: row.departures.slice(0, 2).map((departure) => {
        const minutes = Math.floor((Date.parse(departure.plannedTime) - now) / 60_000);
        return {
          time: departure.plannedTime,
          clock: clock.format(Date.parse(departure.plannedTime)),
          wait: minutes < 1 ? 'now' : `${minutes.toString()} min`,
        };
      }),
    }));
}

function modeRank(type: string): number {
  const rank = modes.indexOf(type);
  return rank === -1 ? modes.length : rank;
}

/**
 * The line's colors for its badge. Some feed colors, such as white on the U3's orange, fall short of
 * the 4.5:1 contrast WCAG AA asks of text, so their badges get black or white text instead.
 */
function badgeColors(line: DepartureLine): { background: string; text: string } {
  const background = line.color ?? modeColor(line.type);
  if (line.textColor && contrast(line.textColor, background) >= 4.5) {
    return { background, text: line.textColor };
  }
  return {
    background,
    text: contrast('#000000', background) > contrast('#ffffff', background) ? '#000000' : '#ffffff',
  };
}

/** The contrast ratio of two `#rrggbb` colors, as WCAG defines it. */
function contrast(first: string, second: string): number {
  const [a, b] = [luminance(first), luminance(second)];
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

function luminance(color: string): number {
  const channel = (start: number) => {
    const value = parseInt(color.slice(start, start + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}
