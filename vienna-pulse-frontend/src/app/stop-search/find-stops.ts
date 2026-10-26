import type { components } from '../timetable/openapi';

/** A stop the search can find, with its name spelled the ways riders type it. */
export interface NamedStop {
  id: string;
  name: string;
  modes: string[];
  /**
   * The words of the name in lower case without accents or punctuation, each with ä, ö and ü as a, o and u
   * and as ae, oe and ue, since a rider may spell each word either way.
   */
  words: string[][];
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
  return stops.features.map(({ id, properties: { name, modes } }) => {
    // Umlauts become letters, never spaces, so both spellings have the same words in the same places.
    const umlauts = fold(name, true).split(' ');
    return {
      id,
      name,
      modes,
      words: fold(name)
        .split(' ')
        .map((word, index) => [...new Set([word, umlauts[index] ?? word])]),
    };
  });
}

// Numbers in names sort by value, so Zentralfriedhof 2.Tor comes before 11.Tor.
const collator = new Intl.Collator('de', { numeric: true });

/**
 * The stops with a word in their name that starts with each word of `query`, so "karls" finds Oper/Karlsplatz
 * but "platz" leaves out Karlsplatz. A name that is the query comes first, then names that start with it, each
 * group in alphabetical order.
 */
export function findStops(stops: readonly NamedStop[], query: string): NamedStop[] {
  const typed = fold(query).split(' ').filter(Boolean);
  if (!typed.length) {
    return [];
  }
  const last = typed.length - 1;
  const found: { stop: NamedStop; rank: number }[] = [];
  for (const stop of stops) {
    const words = stop.words;
    const is = (word: string, index: number) => !!words[index]?.includes(word);
    const starts = (word: string, spellings: string[] | undefined) =>
      !!spellings?.some((spelling) => spelling.startsWith(word));
    let rank: number;
    if (typed.length === words.length && typed.every(is)) {
      rank = 0;
    } else if (typed.every((word, index) => (index < last ? is(word, index) : starts(word, words[index])))) {
      rank = 1;
    } else if (typed.every((word) => words.some((spellings) => starts(word, spellings)))) {
      rank = 2;
    } else {
      continue;
    }
    found.push({ stop, rank });
  }
  return found.sort((a, b) => a.rank - b.rank || collator.compare(a.stop.name, b.stop.name)).map(({ stop }) => stop);
}
