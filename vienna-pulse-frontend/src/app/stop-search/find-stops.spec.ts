import { findStops, namedStops } from './find-stops';

const names = [
  'Karlsplatz',
  'Oper/Karlsplatz',
  'Bösendorferstraße/Karlsplatz',
  'Stockholmer Platz',
  'Döblinger Hauptstraße/Gatterburggasse',
  'Peter-Jordan-Straße',
  'Zentralfriedhof 3.Tor',
  'Zentralfriedhof Halle 3/Gruppe 40',
  'Zentralfriedhof',
  'Vösendorf Shopping City Süd',
];
const stops = namedStops({
  type: 'FeatureCollection',
  features: names.map((name, index) => ({
    type: 'Feature',
    id: `at:49:${index.toString()}`,
    geometry: { type: 'Point', coordinates: [16.37, 48.2] },
    properties: { name, modes: ['bus'] },
  })),
});

describe('findStops', () => {
  it.each([
    // The whole name first, then names that start with the query, then the rest in German alphabetical order.
    ['karls', ['Karlsplatz', 'Bösendorferstraße/Karlsplatz', 'Oper/Karlsplatz']],
    ['KARLSPLATZ', ['Karlsplatz', 'Bösendorferstraße/Karlsplatz', 'Oper/Karlsplatz']],
    ['zentralfriedhof', ['Zentralfriedhof', 'Zentralfriedhof 3.Tor', 'Zentralfriedhof Halle 3/Gruppe 40']],
    // Only words start a match, so a word inside a compound such as Karlsplatz does not.
    ['platz', ['Stockholmer Platz']],
    ['döbling', ['Döblinger Hauptstraße/Gatterburggasse']],
    ['doebling', ['Döblinger Hauptstraße/Gatterburggasse']],
    ['dobling', ['Döblinger Hauptstraße/Gatterburggasse']],
    ['strasse', ['Peter-Jordan-Straße']],
    ['Straße', ['Peter-Jordan-Straße']],
    // Each word may spell its umlaut either way.
    ['voesendorf shopping city süd', ['Vösendorf Shopping City Süd']],
    ['vösendorf sued', ['Vösendorf Shopping City Süd']],
    // Each word of the query starts a word of the name, in any order and with any punctuation.
    ['oper karl', ['Oper/Karlsplatz']],
    ['3 zentral', ['Zentralfriedhof 3.Tor', 'Zentralfriedhof Halle 3/Gruppe 40']],
    ['peter jordan', ['Peter-Jordan-Straße']],
    [' / ', []],
  ])('finds %j', (query, found) => {
    expect(findStops(stops, query).map((stop) => stop.name)).toEqual(found);
  });
});
