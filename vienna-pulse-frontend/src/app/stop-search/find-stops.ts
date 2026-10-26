import type { components } from '../timetable/openapi';

/** A stop the search can find, with its name spelled the ways riders type it. */
export interface NamedStop {
  id: string;
  name: string;
  modes: string[];
  /** The name in lower case without accents or punctuation, with ä, ö and ü as a, o and u and as ae, oe and ue. */
  spellings: string[];
}

/**
 * Writes `text` in lower case with ß as ss, without accents and with single spaces between its words.
 * With `umlauts`, ä, ö and ü become ae, oe and ue as on a keyboard without them, and otherwise a, o and u.
 */
function fold(text: string, umlauts = false): string {
  let folded = text.normalize('NFC').toLowerCase().replaceAll('ß', 'ss');
  if (umlauts) {
    folded = folded.replaceAll('ä', 'ae').replaceAll('ö', 'oe').replaceAll('ü', 'ue');
  }
  return folded
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
}

/** Prepares the stops the API sends for finding them by name. */
export function namedStops(stops: components['schemas']['FeatureCollectionPointStopProperties']): NamedStop[] {
  return stops.features.map(({ id, properties: { name, modes } }) => ({
    id,
    name,
    modes,
    spellings: [...new Set([fold(name), fold(name, true)])],
  }));
}

// Numbers in names sort by value, so Zentralfriedhof 2.Tor comes before 11.Tor.
const collator = new Intl.Collator('de', { numeric: true });

/**
 * The stops with a word in their name that starts with each word of `query`, so "karls" finds Oper/Karlsplatz
 * but "platz" leaves out Karlsplatz. A name that is the query comes first, then names that start with it, each
 * group in alphabetical order.
 */
export function findStops(stops: readonly NamedStop[], query: string): NamedStop[] {
  const typed = fold(query);
  if (!typed) {
    return [];
  }
  const words = typed.split(' ');
  const found: { stop: NamedStop; rank: number }[] = [];
  for (const stop of stops) {
    const ranks = stop.spellings.map((spelling) => {
      if (spelling === typed) return 0;
      if (spelling.startsWith(typed)) return 1;
      return words.every((word) => ` ${spelling}`.includes(` ${word}`)) ? 2 : 3;
    });
    const rank = Math.min(...ranks);
    if (rank < 3) {
      found.push({ stop, rank });
    }
  }
  return found.sort((a, b) => a.rank - b.rank || collator.compare(a.stop.name, b.stop.name)).map(({ stop }) => stop);
}
