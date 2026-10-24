import { linkedSignal } from '@angular/core';
import type { Resource, Signal } from '@angular/core';

/** Follows the last value a resource loaded, kept while it loads again and after a load fails. */
export function latestValue<T>(resource: Resource<T | undefined>): Signal<T | undefined> {
  return linkedSignal<T | undefined, T | undefined>({
    source: () => (resource.hasValue() ? resource.value() : undefined),
    computation: (next, previous) => next ?? previous?.value,
  }).asReadonly();
}
