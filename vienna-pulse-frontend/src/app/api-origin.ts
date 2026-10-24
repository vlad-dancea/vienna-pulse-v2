import { HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { InjectionToken, inject } from '@angular/core';
import { environment } from '../environments/environment';

/** Origin that serves the backend API, or an empty string for the page's own origin. */
export const API_ORIGIN = new InjectionToken<string>('API_ORIGIN', { factory: () => environment.apiOrigin });

/** Sends `/api` requests to the API's origin, so the code that makes them can keep relative paths. */
export function apiOriginInterceptor(request: HttpRequest<unknown>, next: HttpHandlerFn) {
  const origin = inject(API_ORIGIN);
  return next(request.url.startsWith('/api/') ? request.clone({ url: origin + request.url }) : request);
}
