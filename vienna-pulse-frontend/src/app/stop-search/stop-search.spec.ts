import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { ComponentFixture } from '@angular/core/testing';
import type { components } from '../timetable/openapi';
import { StopSearch } from './stop-search';

/** Stops named after Vienna's, with the modes that stop there. */
function stopsNamed(...stops: [string, string[]][]): components['schemas']['FeatureCollectionPointStopProperties'] {
  return {
    type: 'FeatureCollection',
    features: stops.map(([name, modes], index) => ({
      type: 'Feature',
      id: `at:49:${index.toString()}`,
      geometry: { type: 'Point', coordinates: [16.37, 48.2] },
      properties: { name, modes },
    })),
  };
}

describe('StopSearch', () => {
  let fixture: ComponentFixture<StopSearch>;
  let input: HTMLInputElement;
  let picked: string[];

  async function load(stops: components['schemas']['FeatureCollectionPointStopProperties']) {
    fixture = TestBed.createComponent(StopSearch);
    picked = [];
    fixture.componentInstance.picked.subscribe((id) => picked.push(id));
    TestBed.tick();
    TestBed.inject(HttpTestingController).expectOne('/api/stops').flush(stops);
    await fixture.whenStable();
    input = document.getElementById('stop-search') as HTMLInputElement;
    input.focus();
  }

  async function type(text: string) {
    input.value = text;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  /** Presses `key` in the field and tells whether the event went on to the rest of the page. */
  async function press(key: string, init: KeyboardEventInit = {}) {
    let propagated = false;
    const listener = () => (propagated = true);
    document.addEventListener('keydown', listener);
    input.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }));
    document.removeEventListener('keydown', listener);
    await fixture.whenStable();
    return propagated;
  }

  /** The listed stops and their modes, with the selected one marked and the text screen readers hear. */
  function shown() {
    const element = fixture.nativeElement as HTMLElement;
    const expanded = input.getAttribute('aria-expanded');
    const options = Array.from(element.querySelectorAll('[role="option"]'), (option) => {
      const text = option.textContent.replace(/\s+/g, ' ').trim();
      const selected = option.getAttribute('aria-selected') === 'true';
      expect(selected).toBe(input.getAttribute('aria-activedescendant') === option.id);
      return selected ? `> ${text}` : text;
    });
    const popup = element.querySelector<HTMLElement>('[role="listbox"]')?.parentElement;
    return {
      expanded,
      options: expanded === 'true' ? options : [],
      note: popup?.hidden ? undefined : element.querySelector('p')?.textContent.trim(),
      status: element.querySelector('[role="status"]')?.textContent.trim(),
    };
  }

  beforeEach(() => {
    // jsdom does not lay out pages, so it cannot scroll an option into view.
    Element.prototype.scrollIntoView = vi.fn();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    fixture.destroy();
  });

  it('selects the best match while typing, moves with the arrows and opens the selected stop on Enter', async () => {
    await load(
      stopsNamed(
        ['Oper/Karlsplatz', ['bus', 'tram']],
        ['Karlsplatz', ['bus', 'subway', 'tram']],
        ['Bösendorferstraße/Karlsplatz', ['tram']],
        ['Stephansplatz', ['bus', 'subway']],
      ),
    );
    await type('karls');
    expect(shown()).toEqual({
      expanded: 'true',
      options: [
        '> Karlsplatz U-Bahn, tram and bus',
        'Bösendorferstraße/Karlsplatz Tram',
        'Oper/Karlsplatz Tram and bus',
      ],
      note: undefined,
      status: '3 stops match.',
    });

    await press('ArrowDown');
    expect(shown().options[1]).toBe('> Bösendorferstraße/Karlsplatz Tram');
    await press('ArrowUp');
    await press('ArrowUp');
    expect(shown().options[2]).toBe('> Oper/Karlsplatz Tram and bus');
    await press('ArrowDown');
    expect(shown().options[0]).toBe('> Karlsplatz U-Bahn, tram and bus');

    await press('ArrowDown');
    // An input method's Enter confirms the word it composes.
    await press('Enter', { isComposing: true });
    expect(picked).toEqual([]);
    await press('Enter');
    expect(picked).toEqual(['at:49:2']);
    expect(shown()).toEqual({ expanded: 'false', options: [], note: undefined, status: '' });
    expect(input.hasAttribute('aria-activedescendant')).toBe(false);

    // The list comes back with the same matches for picking another one.
    await press('ArrowUp');
    expect(shown().options[2]).toBe('> Oper/Karlsplatz Tram and bus');
    // Alt opens and closes the list without moving the selection.
    await press('ArrowUp', { altKey: true });
    expect(shown().expanded).toBe('false');
    await press('ArrowDown', { altKey: true });
    expect(shown().options[2]).toBe('> Oper/Karlsplatz Tram and bus');
  });

  it('closes the list on Escape, then clears the field, before Escape reaches the departure panel', async () => {
    await load(stopsNamed(['Karlsplatz', ['subway']]));
    await type('karls');

    // An input method's Escape cancels the word it composes.
    expect(await press('Escape', { isComposing: true })).toBe(false);
    expect(shown().expanded).toBe('true');
    expect(await press('Escape')).toBe(false);
    expect(shown().expanded).toBe('false');
    expect(input.value).toBe('karls');
    expect(await press('Escape')).toBe(false);
    expect(input.value).toBe('');
    expect(await press('Escape')).toBe(true);
    expect(await press('Enter')).toBe(true);
    expect(picked).toEqual([]);
  });

  it('finds only the stops of the mode the map shows and says when other modes have some', async () => {
    await load(stopsNamed(['Stephansplatz', ['bus', 'subway']], ['Schwedenplatz', ['subway', 'tram']]));
    fixture.componentRef.setInput('mode', 'tram');
    await type('s');
    expect(shown()).toEqual({
      expanded: 'true',
      options: ['> Schwedenplatz U-Bahn and tram'],
      note: undefined,
      status: '1 tram stop matches.',
    });

    await type('stephans');
    const none = 'No tram stop matches "stephans". Choose All to search every stop.';
    // The popup is open with the message, and its listbox has no options.
    expect(shown()).toEqual({ expanded: 'true', options: [], note: none, status: none });
    expect(await press('Enter')).toBe(true);

    await type('xyz ');
    expect(shown().status).toBe('No tram stop matches "xyz".');
    fixture.componentRef.setInput('mode', 'all');
    await fixture.whenStable();
    expect(shown().status).toBe('No stop matches "xyz".');
  });

  it('lists the first eight matches and says how many more there are', async () => {
    await load(
      stopsNamed(
        ...[1, 2, 3, 4, 9, 11].map((gate): [string, string[]] => [`Zentralfriedhof ${gate.toString()}.Tor`, ['bus']]),
        ['Zentralfriedhof', ['tram']],
        ['Zentralfriedhof Babygruppe', ['bus']],
        ['Zentralfriedhof Halle 3', ['bus']],
        ['Zentralfriedhof Kriegsgräber', ['bus']],
      ),
    );
    await type('zentralfriedhof');
    expect(shown()).toEqual({
      expanded: 'true',
      options: [
        '> Zentralfriedhof Tram',
        'Zentralfriedhof 1.Tor Bus',
        'Zentralfriedhof 2.Tor Bus',
        'Zentralfriedhof 3.Tor Bus',
        'Zentralfriedhof 4.Tor Bus',
        'Zentralfriedhof 9.Tor Bus',
        'Zentralfriedhof 11.Tor Bus',
        'Zentralfriedhof Babygruppe Bus',
      ],
      note: '2 more match. Type more of the name.',
      status: '10 stops match, the first 8 are listed.',
    });
    // Up from the first option wraps to the last one listed.
    await press('ArrowUp');
    expect(shown().options[7]).toBe('> Zentralfriedhof Babygruppe Bus');
  });

  it("ends the list above a phone's keyboard and the map's controls, keeping the selected option in view", async () => {
    const viewport = Object.assign(new EventTarget(), { offsetTop: 0, height: 800 });
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport });
    const box = (top: number) => () => DOMRect.fromRect({ x: 0, y: top, width: 390, height: 40 });
    const resize = async (height: number) => {
      viewport.height = height;
      viewport.dispatchEvent(new Event('resize'));
      await fixture.whenStable();
    };
    try {
      await load(stopsNamed(['Karlsplatz', ['subway']], ['Oper/Karlsplatz', ['tram']]));
      const popup = document.getElementById('stop-search-options')?.parentElement;
      if (!popup) {
        throw new Error('The list has no popup.');
      }
      // The list starts 8 pixels above its top edge, which keeps its shadow clear.
      popup.getBoundingClientRect = box(80);
      await type('karls');
      await press('ArrowDown');
      expect(popup.style.maxHeight).toBe('712px');

      // The map's controls appear once the map loads.
      const controls = document.createElement('div');
      controls.getBoundingClientRect = box(700);
      fixture.componentRef.setInput('floor', controls);
      await fixture.whenStable();
      expect(popup.style.maxHeight).toBe('612px');

      // The keyboard opens and leaves the top 420 pixels visible, and the list scrolls to the selected option.
      const scrolled = vi.fn();
      Element.prototype.scrollIntoView = scrolled;
      await resize(420);
      expect(popup.style.maxHeight).toBe('332px');
      expect(scrolled.mock.contexts).toEqual([document.getElementById('stop-search-at:49:1')]);

      // Without room for two options above the controls, the list covers them, but never the keyboard.
      controls.getBoundingClientRect = box(150);
      await resize(420);
      expect(popup.style.maxHeight).toBe('112px');
      await resize(150);
      expect(popup.style.maxHeight).toBe('62px');
    } finally {
      Reflect.deleteProperty(window, 'visualViewport');
    }
  });
});
