import { modeColor } from '../map-view/lines';
import type { Departure, DepartureLine } from '../timetable/stop-departures';

/** A line towards one headsign and its next departures, as one row of the board. */
export interface BoardRow {
  key: string;
  line: string;
  background: string;
  text: string;
  headsign: string;
  /** Which platforms the departures leave from, such as "Platform C", or empty when the live data names none. */
  platform: LabelPart[];
  departures: BoardDeparture[];
}

/** A piece of a row's platform label: a code that signs show, or the words around it. */
export interface LabelPart {
  text: string;
  code: boolean;
}

/** One departure in a row of the board. */
export interface BoardDeparture {
  /** When it leaves, at its live time where Wiener Linien have one and its planned time otherwise. */
  time: string;
  clock: string;
  wait: string;
  /** Whether Wiener Linien have a live time for it. */
  live: boolean;
  /** The planned clock time, shown struck through when the live time is a minute or more away from it. */
  planned: string | null;
  /** Whether it leaves so much later than planned that the board marks it. */
  late: boolean;
}

// The order of the map's mode filter. Other modes follow the buses.
const modes = ['subway', 'tram', 'bus'];

const clock = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Vienna', hour: '2-digit', minute: '2-digit' });

// How late a departure has to be for the board to mark it.
const lateAfter = 3 * 60_000;

/**
 * Groups the departures that have not left at `now` by line and headsign, with each group's next two,
 * like the displays at the stops. A departure leaves at its live time where there is one, which can be
 * an hour after its planned time. Rows are sorted by mode and line, so a line keeps its place as
 * departures leave and the board refreshes.
 */
export function boardRows(departures: readonly Departure[], now: number): BoardRow[] {
  const rows = new Map<string, { line: DepartureLine; headsign: string | null; departures: Departure[] }>();
  for (const departure of departures) {
    if (Date.parse(departure.liveTime ?? departure.plannedTime) < now) {
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
    .map(([key, row]) => {
      const next = row.departures.slice(0, 2);
      return {
        key,
        line: row.line.name,
        ...badgeColors(row.line),
        // The displays at the stops leave out the "Wien " that most headsigns start with.
        headsign: row.headsign?.replace(/^Wien /, '') ?? '',
        platform: platformLabel(next),
        departures: next.map((departure) => boardDeparture(departure, now)),
      };
    });
}

/**
 * Names the platforms that a row's departures leave from by the codes signs show. Wiener Linien name
 * the platform only of a departure with a live time, so one without takes the code of the row's other
 * departure when the timetable has both at the same platform. A departure with a live time and no
 * code leaves from a platform without one. Departures from different platforms are named in turn,
 * like the times beside them.
 */
function platformLabel(departures: readonly Departure[]): LabelPart[] {
  const codes = departures.map((departure) =>
    departure.liveTime === null
      ? (departures.find((other) => other.platform === departure.platform && other.platformCode !== null)
          ?.platformCode ?? null)
      : departure.platformCode,
  );
  const [first = null, second = first] = codes;
  if (first !== null && second !== null) {
    return first === second ? label('Platform ', first) : label('Platform ', first, ', then ', second);
  }
  if (first !== null) {
    return label('Next from platform ', first);
  }
  return second === null ? [] : label('Then from platform ', second);
}

// Words and codes take turns, starting with words.
function label(...texts: string[]): LabelPart[] {
  return texts.map((text, index) => ({ text, code: index % 2 === 1 }));
}

function boardDeparture(departure: Departure, now: number): BoardDeparture {
  const time = departure.liveTime ?? departure.plannedTime;
  const leaves = Date.parse(time);
  const planned = Date.parse(departure.plannedTime);
  const minutes = Math.floor((leaves - now) / 60_000);
  return {
    time,
    clock: clock.format(leaves),
    wait: minutes < 1 ? 'now' : `${minutes.toString()} min`,
    live: departure.liveTime !== null,
    planned: Math.abs(leaves - planned) >= 60_000 ? clock.format(planned) : null,
    late: leaves - planned >= lateAfter,
  };
}

function modeRank(type: string): number {
  const rank = modes.indexOf(type);
  return rank === -1 ? modes.length : rank;
}

/**
 * The line's colors for its badge: the feed's, or white text on the mode's color for a line without
 * them. White on the U2's, U3's and U4's colors falls short of the 4.5:1 contrast WCAG AA asks of
 * text, but it is the feed's own choice, and the line names are short and bold.
 */
function badgeColors(line: DepartureLine): { background: string; text: string } {
  return { background: line.color ?? modeColor(line.type), text: line.textColor ?? '#ffffff' };
}
