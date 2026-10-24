import { DestroyRef, DOCUMENT, inject } from '@angular/core';

/** Calls `refresh` every `interval` milliseconds and on each of `events` until the caller is destroyed. */
export function refreshEvery(interval: number, refresh: () => void, events: ('visibilitychange' | 'focus')[] = []) {
  const document = inject(DOCUMENT);
  const timer = setInterval(refresh, interval);
  const listeners = new AbortController();
  for (const event of events) {
    // The document reports visibility changes, and the window reports focus.
    const target = event === 'focus' ? document.defaultView : document;
    target?.addEventListener(event, refresh, { signal: listeners.signal });
  }
  inject(DestroyRef).onDestroy(() => {
    clearInterval(timer);
    listeners.abort();
  });
}
