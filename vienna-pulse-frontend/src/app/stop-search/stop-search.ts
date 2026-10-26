import {
  afterRenderEffect,
  Component,
  computed,
  DestroyRef,
  DOCUMENT,
  ElementRef,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { modeNames } from '../mode-names';
import { NetworkStops } from '../timetable/network-stops';
import { findStops, namedStops } from './find-stops';

/** How many matches the list shows, so that a few letters do not fill the screen. */
const shownMatches = 8;

const modeList = new Intl.ListFormat('en-GB', { type: 'conjunction' });

/** Lists the modes that call at a stop the way a sentence does, in the order of the map's filter, such as "Tram and bus". */
function describeModes(modes: string[]): string {
  const text = modeList.format(
    Object.entries(modeNames).flatMap(([mode, name]) => (modes.includes(mode) ? [name] : [])),
  );
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Says how many stops match, such as "1 tram stop matches". */
function countStops(count: number, mode: string): string {
  const kind = modeNames[mode] ? `${modeNames[mode]} stop` : 'stop';
  return count === 1 ? `1 ${kind} matches` : `${count.toString()} ${kind}s match`;
}

/**
 * Finds a stop by its name and opens it, as an editable combobox with a list of matches following the WAI-ARIA
 * pattern. Typing selects the best match, Down and Up move through the others, Enter opens the selected stop,
 * and Escape closes the list and then clears the field.
 */
@Component({
  selector: 'app-stop-search',
  templateUrl: './stop-search.html',
  host: { role: 'search', class: 'relative block' },
})
export class StopSearch {
  /** The transport mode the map shows, such as `tram`, so the search finds only the stops on the map, or `all`. */
  readonly mode = input('all');
  /** The map's controls at the bottom of the screen, which the list ends above. */
  readonly floor = input<HTMLElement>();
  /** Emits the id of the stop a rider picks. */
  readonly picked = output<string>();

  private readonly stops = inject(NetworkStops);
  private readonly named = computed(() => {
    const latest = this.stops.latest();
    return latest && namedStops(latest);
  });
  protected readonly query = signal('');
  protected readonly opened = signal(false);
  private readonly matches = computed(() => {
    const mode = this.mode();
    const stops = this.named() ?? [];
    return findStops(mode === 'all' ? stops : stops.filter((stop) => stop.modes.includes(mode)), this.query());
  });
  protected readonly options = computed(() =>
    this.matches()
      .slice(0, shownMatches)
      .map((stop) => ({ id: `stop-search-${stop.id}`, stop, modes: describeModes(stop.modes) })),
  );
  /** The index of the option Enter opens, which goes back to the best match whenever the matches change. */
  protected readonly active = linkedSignal({ source: this.options, computation: () => 0 });
  /** Whether the popup is open, with the matches or a message saying why there are none. */
  protected readonly shown = computed(() => this.opened() && this.query().trim() !== '');
  protected readonly expanded = computed(() => this.shown() && this.options().length > 0);
  protected readonly activeId = computed(() => (this.expanded() ? this.options()[this.active()]?.id : undefined));
  /** What the popup says below or instead of the options. */
  protected readonly note = computed(() => {
    const named = this.named();
    if (!named) {
      return this.stops.stops.error() ? 'Stops could not be loaded.' : 'Loading stops...';
    }
    const more = this.matches().length - shownMatches;
    if (more > 0) {
      return `${more.toString()} more ${more === 1 ? 'matches' : 'match'}. Type more of the name.`;
    }
    if (this.matches().length) {
      return '';
    }
    const mode = this.mode();
    const none = `No ${modeNames[mode] ? `${modeNames[mode]} ` : ''}stop matches "${this.query().trim()}".`;
    // The map's filter hides the other modes' stops, so the rider learns that they exist.
    return mode !== 'all' && findStops(named, this.query()).length ? `${none} Choose All to search every stop.` : none;
  });
  /** What screen readers announce, since they cannot see how long the list is. */
  protected readonly status = computed(() => {
    if (!this.shown()) {
      return '';
    }
    const count = this.matches().length;
    if (!count) {
      return this.note();
    }
    const listed = count > shownMatches ? `, the first ${shownMatches.toString()} are listed` : '';
    return `${countStops(count, this.mode())}${listed}.`;
  });

  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('input');
  private readonly popup = viewChild.required<ElementRef<HTMLElement>>('popup');

  /** Moves focus to the field, as when the stop it opened closes. */
  focus() {
    this.input().nativeElement.focus();
  }

  protected type(query: string) {
    this.query.set(query);
    this.opened.set(true);
  }

  protected press(event: KeyboardEvent) {
    // While an input method composes a word, its Enter and arrows choose the word, not a stop.
    if (event.isComposing) {
      return;
    }
    const down = event.key === 'ArrowDown';
    if (down || event.key === 'ArrowUp') {
      // The arrows would move the caret to the start or end of the field.
      event.preventDefault();
      const count = this.options().length;
      if (event.altKey) {
        this.opened.set(down);
      } else if (!this.expanded()) {
        this.opened.set(true);
        this.active.set(down ? 0 : Math.max(count - 1, 0));
      } else {
        this.active.update((index) => (index + (down ? 1 : count - 1)) % count);
      }
    } else if (event.key === 'Enter') {
      const option = this.expanded() ? this.options()[this.active()] : undefined;
      if (option) {
        event.preventDefault();
        this.pick(option.stop.id);
      }
    } else if (event.key === 'Escape' && (this.shown() || this.query())) {
      // The departure panel closes on Escape anywhere, so it hears only an Escape the field has no use for.
      event.stopPropagation();
      if (this.shown()) {
        this.opened.set(false);
      } else {
        this.query.set('');
      }
    }
  }

  protected pick(id: string) {
    this.opened.set(false);
    this.picked.emit(id);
  }

  constructor() {
    const document = inject(DOCUMENT);
    const window = document.defaultView;

    // A phone's keyboard covers the bottom of the page without resizing it, and the visual viewport is what
    // stays visible above the keyboard. The map's controls sit above the departure sheet while it is open.
    const fit = () => {
      if (!this.shown()) {
        return;
      }
      const popup = this.popup().nativeElement;
      const viewport = window?.visualViewport;
      const visible = viewport ? viewport.offsetTop + viewport.height : (window?.innerHeight ?? 0);
      const bottom = Math.min(visible, this.floor()?.getBoundingClientRect().top ?? visible);
      // The margin keeps the popup's shadow clear, and two options always fit.
      popup.style.maxHeight = `${Math.max(bottom - popup.getBoundingClientRect().top - 8, 112).toString()}px`;
    };
    const listeners = new AbortController();
    window?.addEventListener('resize', fit, { signal: listeners.signal });
    window?.visualViewport?.addEventListener('resize', fit, { signal: listeners.signal });
    window?.visualViewport?.addEventListener('scroll', fit, { signal: listeners.signal });
    inject(DestroyRef).onDestroy(() => {
      listeners.abort();
    });
    afterRenderEffect(() => {
      if (this.shown()) {
        untracked(fit);
      }
    });

    afterRenderEffect(() => {
      const id = this.activeId();
      if (id) {
        untracked(() => document.getElementById(id)?.scrollIntoView({ block: 'nearest' }));
      }
    });
  }
}
