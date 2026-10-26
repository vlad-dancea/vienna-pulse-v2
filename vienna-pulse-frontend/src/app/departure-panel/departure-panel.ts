import { HttpErrorResponse } from '@angular/common/http';
import {
  afterRenderEffect,
  Component,
  computed,
  DOCUMENT,
  effect,
  ElementRef,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { modeNames } from '../mode-names';
import { latestValue } from '../timetable/latest-value';
import { refreshEvery } from '../timetable/refresh-every';
import { stopDepartures } from '../timetable/stop-departures';
import { boardRows } from './departure-board';

/** Shows a stop's next departures grouped by line and headsign, beside the map or as a sheet below it on phones. */
@Component({
  selector: 'app-departure-panel',
  templateUrl: './departure-panel.html',
  host: {
    role: 'region',
    'aria-labelledby': 'departures-heading',
    // The map measures this box to keep the open stop out from under it. On wide screens it ends
    // above the map's attribution, and on phones styles.css moves the attribution above it.
    class:
      'absolute inset-x-0 bottom-0 z-10 h-[50dvh] overflow-y-auto rounded-t-2xl border border-slate-200 bg-white text-slate-900 shadow-lg md:inset-x-auto md:top-6 md:right-6 md:bottom-auto md:h-auto md:max-h-[calc(100%-4.5rem)] md:w-96 md:rounded-2xl',
    '(document:keydown.escape)': 'closed.emit()',
  },
})
export class DeparturePanel {
  /** The id of the stop, as the map and the API know it. */
  readonly stop = input.required<string>();
  /** The stop's name on the map, shown while its departures load. */
  readonly name = input<string>();
  /** The transport mode the map's filter shows, such as `subway`, so the board shows only its lines, or `all`. */
  readonly mode = input('all');
  /** Emits when the close button or Escape asks to close the panel. */
  readonly closed = output();

  // The loaders and the faster refresh follow the same answer, which stays while a reload fails.
  protected readonly departures = stopDepartures(this.stop, () => this.loaded());
  private readonly latest = latestValue(this.departures);
  /** The last departures that loaded for this stop, kept while they reload and after a reload fails. */
  private readonly loaded = computed(() => {
    const latest = this.latest();
    return latest?.stop.id === this.stop() ? latest : undefined;
  });
  private readonly now = signal(Date.now());
  protected readonly rows = computed(() => {
    const mode = this.mode();
    const departures = this.loaded()?.departures ?? [];
    return boardRows(
      mode === 'all' ? departures : departures.filter((departure) => departure.line.type === mode),
      this.now(),
    );
  });
  /** Whether the backend has yet to fetch the stop's live times, which it does within 15 seconds. */
  protected readonly pending = computed(() => this.loaded()?.liveStatus === 'pending');
  protected readonly nothingLeaves = computed(() => {
    const name = modeNames[this.mode()];
    return name ? `No ${name} departures in the next hour.` : 'No departures in the next hour.';
  });
  protected readonly title = computed(() => this.name() ?? this.loaded()?.stop.name ?? 'Departures');
  /** Whether the API knows no such stop, as after an import that removed one a link points to. */
  protected readonly unknown = computed(() => {
    const error = this.departures.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });
  protected readonly retryShown = computed(() => !!this.departures.error() && !this.unknown() && !this.rows().length);

  private readonly headingElement = viewChild.required<ElementRef<HTMLElement>>('heading');

  /** Moves focus to the stop's name, as when the panel opens. */
  focus() {
    this.headingElement().nativeElement.focus();
  }

  protected retry() {
    // The error and retry button disappear after success, so focus the panel's persistent heading.
    this.headingElement().nativeElement.focus();
    this.departures.reload();
  }

  constructor() {
    const document = inject(DOCUMENT);

    // The minutes count down between refreshes, and departures that left drop off.
    refreshEvery(
      10_000,
      () => {
        this.now.set(Date.now());
      },
      ['visibilitychange'],
    );

    // Focus moves to the stop's name whenever the panel shows another stop.
    afterRenderEffect(() => {
      this.stop();
      untracked(() => {
        this.headingElement().nativeElement.focus();
      });
    });

    effect(() => {
      // Refreshing also removes the retry button, so preserve focus before Angular updates the view.
      if (!this.retryShown() && document.activeElement?.id === 'retry-departures') {
        this.headingElement().nativeElement.focus();
      }
    });
  }
}
